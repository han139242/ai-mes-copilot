package com.neuedu.ems.util;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.converters.Converter;
import com.alibaba.excel.enums.CellDataTypeEnum;
import com.alibaba.excel.metadata.GlobalConfiguration;
import com.alibaba.excel.metadata.data.WriteCellData;
import com.alibaba.excel.metadata.property.ExcelContentProperty;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.exception.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * Excel 通用导出工具类
 */
@Component
public class ExcelExportUtil {

    private static final Logger log = LoggerFactory.getLogger(ExcelExportUtil.class);

    /**
     * 导出 Excel（单个 sheet）。
     * <p>
     * 先把工作簿完整写入内存，确认没有异常后再设置下载响应头并输出字节流。
     * 这样一旦导出失败，响应头仍是干净的，GlobalExceptionHandler 可以正常返回 JSON 错误体，
     * 不会出现 "No converter for [class java.util.LinkedHashMap] with preset Content-Type ..." 的二次异常。
     *
     * @param response Http响应对象（用于下载文件）
     * @param data 导出数据列表
     * @param clazz 实体类Class（指定表头映射）
     * @param fileName 导出文件名（不含.xlsx后缀）
     * @param sheetName sheet名称
     */
    public void exportExcel(HttpServletResponse response, List<?> data, Class<?> clazz,
                           String fileName, String sheetName) {
        // 1. 先在内存里把 Excel 写完。此时还没有动过 response，失败也不会污染响应头。
        byte[] bytes = writeToBytes(data, clazz, sheetName);

        // 2. 写入成功后才设置下载响应头，避免异常响应被 xlsx 的 Content-Type 卡住
        try {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            // URLEncoder.encode 解决中文文件名乱码
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8.toString()).replaceAll("\\+", "%20");
            response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + encodedFileName + ".xlsx");
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (Exception e) {
            log.error("Excel 响应写出失败，文件名：{}", fileName, e);
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "Excel 导出失败，请稍后重试");
        }
    }

    /**
     * 把工作簿写入内存字节数组。任何转换/写出异常都在这里抛出，
     * 转换成用户可读的业务异常，不把底层堆栈文案暴露给前端。
     */
    private byte[] writeToBytes(List<?> data, Class<?> clazz, String sheetName) {
        List<?> rows = data == null ? Collections.emptyList() : data;
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            EasyExcel.write(outputStream, clazz)
                    .registerConverter(new LocalTimeConverter()) // EasyExcel 3.3.2 没有内置 LocalTime 转换器
                    .sheet(sheetName) // 设置sheet名称
                    .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy()) // 自动适配列宽
                    .doWrite(rows); // 写入数据
            return outputStream.toByteArray();
        } catch (Exception e) {
            // 底层异常（例如实体字段类型没有对应转换器）只写日志，给前端返回可读文案
            log.error("Excel 数据写出失败，实体：{}", clazz == null ? "null" : clazz.getName(), e);
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "Excel 导出失败，请稍后重试");
        }
    }

    /**
     * java.time.LocalTime 转换器。
     * EasyExcel 3.3.2 内置转换器只覆盖 LocalDate / LocalDateTime / Date，
     * 实体里出现 LocalTime 字段（如 OrgShift.startTime / endTime）时会抛
     * "Can not find 'Converter' support class LocalTime."，这里补上。
     */
    private static class LocalTimeConverter implements Converter<LocalTime> {

        private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

        @Override
        public Class<?> supportJavaTypeKey() {
            return LocalTime.class;
        }

        @Override
        public CellDataTypeEnum supportExcelTypeKey() {
            return CellDataTypeEnum.STRING;
        }

        @Override
        public WriteCellData<?> convertToExcelData(LocalTime value, ExcelContentProperty contentProperty,
                                                   GlobalConfiguration globalConfiguration) {
            if (value == null) {
                return new WriteCellData<>(CellDataTypeEnum.EMPTY);
            }
            return new WriteCellData<>(TIME_FORMATTER.format(value));
        }
    }
}
