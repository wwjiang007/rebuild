/*!
Copyright (c) REBUILD <https://getrebuild.com/> and/or its owners. All rights reserved.

rebuild is dual-licensed under commercial and open source licenses (GPLv3).
See LICENSE and COMMERCIAL in the project root for license information.
*/

package com.rebuild.core.service.datareport;

import cn.devezhao.commons.CalendarUtils;
import cn.devezhao.persist4j.Entity;
import cn.devezhao.persist4j.Field;
import cn.devezhao.persist4j.Record;
import cn.devezhao.persist4j.engine.ID;
import com.rebuild.core.Application;
import com.rebuild.core.metadata.EntityHelper;
import com.rebuild.core.metadata.MetadataHelper;
import com.rebuild.core.privileges.UserService;
import com.rebuild.core.service.approval.ApprovalHelper;
import com.rebuild.core.support.general.ProtocolFilterParser;
import com.rebuild.core.support.general.RecordBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.util.Assert;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.rebuild.core.service.datareport.TemplateExtractor.APPROVAL_PREFIX;
import static com.rebuild.core.service.datareport.TemplateExtractor.NROW_PREFIX;
import static com.rebuild.core.service.datareport.TemplateExtractor33.DETAIL_PREFIX;

/**
 * V33
 *
 * @author devezhao
 * @since 2023/4/5
 */
@Slf4j
public class EasyExcelGenerator33 extends EasyExcelGenerator {

    final private List<ID> recordIdMultiple;

    protected EasyExcelGenerator33(File templateFile, ID recordId) {
        super(templateFile, recordId);
        this.recordIdMultiple = null;
    }

    protected EasyExcelGenerator33(File template, List<ID> recordIds) {
        super(template, recordIds.get(0));
        this.recordIdMultiple = recordIds;
    }

    @Override
    protected List<Map<String, Object>> buildData() {
        final Entity entity = MetadataHelper.getEntity(recordId.getEntityCode());

        final TemplateExtractor33 templateExtractor33 = this.buildTemplateExtractor33();
        final Map<String, String> varsMap = templateExtractor33.transformVars(entity);

        // 变量
        Map<String, String> varsMapOfMain = new HashMap<>();
        Map<String, Map<String, String>> varsMapOfRefs = new HashMap<>();
        // 字段
        List<String> fieldsOfMain = new ArrayList<>();
        Map<String, List<String>> fieldsOfRefs = new HashMap<>();

        for (Map.Entry<String, String> e : varsMap.entrySet()) {
            final String varName = e.getKey();
            final String fieldName = e.getValue();

            String refName = null;
            if (varName.startsWith(NROW_PREFIX)) {
                if (varName.startsWith(APPROVAL_PREFIX)) {
                    refName = APPROVAL_PREFIX;
                } else if (varName.startsWith(DETAIL_PREFIX)) {
                    refName = DETAIL_PREFIX;
                } else {
                    // 在客户中导出订单（下列 AccountId 为订单中引用客户的引用字段）
                    // .AccountId.SalesOrder.SalesOrderName
                    String[] split = varName.substring(1).split("\\.");
                    if (split.length < 2) throw new ReportsException("Bad REF (Miss .detail prefix?) : " + varName);

                    String refName2 = split[0] + split[1];
                    refName = varName.substring(0, refName2.length() + 2 /* dots */);
                }

                Map<String, String> varsMapOfRef = varsMapOfRefs.getOrDefault(refName, new HashMap<>());
                varsMapOfRef.put(varName, fieldName);
                varsMapOfRefs.put(refName, varsMapOfRef);

            } else {
                varsMapOfMain.put(varName, fieldName);
            }

            // 占位字段
            if (TemplateExtractor33.isPlaceholder(varName)) continue;
            // 无效字段
            if (fieldName == null) {
                log.warn("Invalid field `{}` in template : {}", e.getKey(), templateFile);
                continue;
            }

            if (varName.startsWith(NROW_PREFIX)) {
                List<String> fieldsOfRef = fieldsOfRefs.getOrDefault(refName, new ArrayList<>());
                fieldsOfRef.add(fieldName);
                fieldsOfRefs.put(refName, fieldsOfRef);
            } else {
                fieldsOfMain.add(fieldName);
            }
        }

        if (fieldsOfMain.isEmpty() && fieldsOfRefs.isEmpty()) {
            return Collections.emptyList();
        }

        final List<Map<String, Object>> datas = new ArrayList<>();
        final String baseSql = "select %s,%s from %s where %s = ?";

        if (!fieldsOfMain.isEmpty()) {
            String sql = String.format(baseSql,
                    StringUtils.join(fieldsOfMain, ","),
                    entity.getPrimaryField().getName(), entity.getName(), entity.getPrimaryField().getName());

            Record record = Application.createQuery(sql, this.getUser())
                    .setParameter(1, recordId)
                    .record();
            Assert.notNull(record, "No record found : " + recordId);

            this.hasMain = true;
            datas.add(buildData(record, varsMapOfMain));
        }

        for (Map.Entry<String, List<String>> e : fieldsOfRefs.entrySet()) {
            final String refName = e.getKey();
            final boolean isApproval = refName.startsWith(APPROVAL_PREFIX);

            String querySql = baseSql;
            if (isApproval) {
                querySql += " and isWaiting = 'F' and isCanceled = 'F' order by createdOn";
                querySql = String.format(querySql, StringUtils.join(e.getValue(), ","),
                        "createdOn,recordId,state,stepId", "RobotApprovalStep", "recordId");

            } else if (refName.startsWith(DETAIL_PREFIX)) {
                Entity de = entity.getDetailEntity();

                String sortField = templateExtractor33.getSortField(DETAIL_PREFIX);
                querySql += " order by " + StringUtils.defaultIfBlank(sortField, "createdOn asc");

                querySql = String.format(querySql, StringUtils.join(e.getValue(), ","),
                        de.getPrimaryField().getName(), de.getName(), MetadataHelper.getDetailToMainField(de).getName());

            } else {
                String[] split = refName.substring(1).split("\\.");
                Field ref2Field = MetadataHelper.getField(split[1], split[0]);
                Entity ref2Entity = ref2Field.getOwnEntity();

                String sortField = templateExtractor33.getSortField(refName);
                querySql += " order by " + StringUtils.defaultIfBlank(sortField, "createdOn asc");

                String relatedExpr = split[1] + "." + split[0];
                String where = new ProtocolFilterParser().parseRelated(relatedExpr, recordId);
                querySql = querySql.replace("%s = ?", where);

                querySql = String.format(querySql, StringUtils.join(e.getValue(), ","),
                        ref2Entity.getPrimaryField().getName(), ref2Entity.getName());
            }

            log.info("SQL of template : {}", querySql);
            List<Record> list = Application.createQuery(querySql, isApproval ? UserService.SYSTEM_USER : getUser())
                    .setParameter(1, recordId)
                    .list();

            // 补充提交节点
            if (isApproval && !list.isEmpty()) {
                Record firstNode = list.get(0);
                Record submit = RecordBuilder.builder(EntityHelper.RobotApprovalStep)
                        .add("approver", ApprovalHelper.getSubmitter(firstNode.getID("recordId")))
                        .add("approvedTime", CalendarUtils.getUTCDateTimeFormat().format(firstNode.getDate("createdOn")))
                        .add("state", 0)
                        .build(UserService.SYSTEM_USER);

                List<Record> list2 = new ArrayList<>();
                list2.add(submit);
                list2.addAll(list);
                list = list2;
            }

            phNumber = 1;
            for (Record c : list) {
                // 特殊处理
                if (isApproval) {
                    int state = c.getInt("state");
                    Date approvedTime = c.getDate("approvedTime");
                    if (approvedTime == null && state > 1) c.setDate("approvedTime", c.getDate("createdOn"));
                }
                
                datas.add(buildData(c, varsMapOfRefs.get(refName)));
                phNumber++;
            }
        }

        return datas;
    }

    /**
     * @return
     */
    protected TemplateExtractor33 buildTemplateExtractor33() {
        return new TemplateExtractor33(templateFile);
    }

    // -- V34 支持多记录导出

    @Override
    public File generate() {
        if (recordIdMultiple == null) return super.generate();

        // init
        File targetFile = super.getTargetFile();
        try {
            FileUtils.copyFile(templateFile, targetFile);
        } catch (IOException e) {
            throw new ReportsException(e);
        }

        PrintSetup copyPrintSetup = null;
        for (ID recordId : recordIdMultiple) {
            int newSheetAt;
            try (Workbook wb = WorkbookFactory.create(Files.newInputStream(targetFile.toPath()))) {
                // 1.复制模板
                Sheet newSheet = wb.cloneSheet(0);
                newSheetAt = wb.getSheetIndex(newSheet);
                String newSheetName = "A" + newSheetAt;
                try {
                    wb.setSheetName(newSheetAt, newSheetName);
                } catch (IllegalArgumentException ignored) {
                    newSheetName = recordId.toLiteral().toUpperCase();
                    wb.setSheetName(newSheetAt, newSheetName);
                }

                // 2.复制打印设置（POI 不会自己复制???）
                // https://www.bilibili.com/read/cv15053559/
                if (copyPrintSetup == null) {
                    copyPrintSetup = wb.getSheetAt(0).getPrintSetup();
                }
                newSheet.getPrintSetup().setLandscape(copyPrintSetup.getLandscape());
                newSheet.getPrintSetup().setPaperSize(copyPrintSetup.getPaperSize());

                // 3.保存模板
                try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                    wb.write(fos);
                }

            } catch (IOException e) {
                throw new ReportsException(e);
            }

            // 生成报表
            this.templateFile = targetFile;
            this.writeSheetAt = newSheetAt;
            this.recordId = recordId;
            this.hasMain = false;
            this.phNumber = 1;
            this.phValues.clear();

            targetFile = super.generate();
        }

        // 删除模板 Sheet
        try (Workbook wb = WorkbookFactory.create(Files.newInputStream(targetFile.toPath()))) {
            wb.removeSheetAt(0);
            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                wb.write(fos);
            }
        } catch (IOException e) {
            throw new ReportsException(e);
        }

        return targetFile;
    }
}
