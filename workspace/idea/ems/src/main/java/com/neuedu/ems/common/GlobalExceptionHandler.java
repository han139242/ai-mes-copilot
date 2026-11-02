package com.neuedu.ems.common;

import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.exception.AiServiceException;
import com.neuedu.ems.exception.AccountStatusException;
import com.neuedu.ems.exception.OptimisticLockConflictException;
import com.neuedu.ems.exception.DataScopeAccessDeniedException;
import com.neuedu.ems.exception.SensitivePhoneAccessDeniedException;
import com.neuedu.ems.exception.SensitivePhoneCryptoException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一捕获并处理所有控制器层抛出的异常
 */
//@Slf4j
@RestControllerAdvice  // 等同于 @ControllerAdvice + @ResponseBody
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 连接层失败的异常类型清单：JDBC、事务与网络三类，统一在此维护。 */
    private static final java.util.List<Class<? extends Throwable>> CONNECTION_FAILURE_TYPES = java.util.List.of(
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.jdbc.CannotGetJdbcConnectionException.class,
            org.springframework.transaction.CannotCreateTransactionException.class,
            java.sql.SQLTransientConnectionException.class,
            java.sql.SQLNonTransientConnectionException.class,
            java.net.ConnectException.class,
            java.net.SocketTimeoutException.class,
            java.net.UnknownHostException.class);

    @ExceptionHandler(DataScopeAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResultData handleDataScopeAccessDenied(DataScopeAccessDeniedException e) {
        log.info("数据范围拒绝：{}", e.getMessage());
        // 拒绝原因要带回前端：只给「无权限」会让人分不清是权限不够还是参数写错。
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = ResultCode.FORBIDDEN.getMessage();
        }
        return ResultData.fail(ResultCode.FORBIDDEN, Map.of("message", message));
    }

    @ExceptionHandler(SensitivePhoneAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResultData handleSensitivePhoneAccessDenied(SensitivePhoneAccessDeniedException e) {
        log.info("联系电话字段权限拒绝");
        return ResultData.fail(ResultCode.FORBIDDEN);
    }

    @ExceptionHandler(SensitivePhoneCryptoException.class)
    public ResultData handleSensitivePhoneCrypto(SensitivePhoneCryptoException e) {
        log.error("联系电话加密服务失败：{}", e.getClass().getSimpleName());
        return ResultData.fail(ResultCode.BUSINESS_ERROR,
                Map.of("message", "联系电话敏感数据暂不可用"));
    }

    /**
     * 返回明确的账号停用、锁定或状态异常提示。
     */
    @ExceptionHandler(AccountStatusException.class)
    public ResultData handleAccountStatusException(AccountStatusException e) {
        log.info("账号登录受限：{}", e.getMessage());
        return ResultData.fail(e.getResultCode());
    }

    @ExceptionHandler(OptimisticLockConflictException.class)
    public ResultData handleOptimisticLockConflict(OptimisticLockConflictException e) {
        log.info("乐观锁冲突：{}", e.getMessage());
        return ResultData.fail(ResultCode.DATA_CONFLICT);
    }

    @ExceptionHandler(MyBatisSystemException.class)
    public ResultData handleMyBatisSystemException(MyBatisSystemException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockConflictException conflict) {
                return handleOptimisticLockConflict(conflict);
            }
        }
        if (isConnectionUnavailable(e)) {
            return handleConnectionUnavailable(e, null);
        }
        log.error("数据库访问异常", e);
        return ResultData.fail(ResultCode.FAILED);
    }

    /**
     * 数据库连不上时给可区分的提示：这不是业务规则拒绝，也不是数据冲突，
     * 让调用方知道「等数据库恢复后重试」即可，不必按系统故障排查。
     */
    @ExceptionHandler({
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.jdbc.CannotGetJdbcConnectionException.class,
            org.springframework.transaction.CannotCreateTransactionException.class
    })
    public ResultData handleConnectionUnavailable(Exception e, jakarta.servlet.http.HttpServletRequest request) {
        String where = request == null ? "" : request.getMethod() + " " + request.getRequestURI();
        log.error("数据库连接不可用{}：{}", where.isEmpty() ? "" : "（" + where + "）", rootMessage(e));
        return ResultData.fail(ResultCode.FAILED,
                Map.of("message", "数据库暂时连不上，请确认数据库已启动后重试；本次操作没有生效"));
    }

    /**
     * 连接层失败（拒绝连接、连接超时、链路中断）的判定：沿 cause 链找 JDBC 与网络异常。
     */
    private boolean isConnectionUnavailable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (isConnectionFailureType(cause)) {
                return true;
            }
        }
        return false;
    }

    /** 单个 cause 是否属于连接层失败：用类型清单收敛成一处置维护。 */
    private static boolean isConnectionFailureType(Throwable cause) {
        for (Class<? extends Throwable> type : CONNECTION_FAILURE_TYPES) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    /**
     * 处理自定义业务异常（优先级最高）
     */
    @ExceptionHandler(BusinessException.class)
    public ResultData handleBusinessException(BusinessException e) {
        ResultCode resultCode = Arrays.stream(ResultCode.values())
                .filter(code -> code.getCode() == e.getCode())
                .findFirst()
                .orElse(ResultCode.BUSINESS_ERROR);
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = resultCode.getMessage();
        }
        log.info("业务请求被拒绝：{}", message);
        return ResultData.fail(resultCode,
                Map.of("message", message, "businessCode", e.getCode()));
    }

    /**
     * 处理参数校验异常（@Valid/@Validated 校验失败）
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResultData handleValidationException(Exception e) {
        String errorMsg;

        if (e instanceof MethodArgumentNotValidException) {
            // 请求体参数校验失败
            MethodArgumentNotValidException ex = (MethodArgumentNotValidException) e;

            errorMsg = ex.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .collect(Collectors.joining("；"));
        } else {
            // 请求参数绑定校验失败（如路径参数、请求头参数）
            BindException ex = (BindException) e;
            errorMsg = ex.getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .collect(Collectors.joining("；"));
        }
        log.warn("参数校验异常：{}", errorMsg);
        return ResultData.fail(ResultCode.PARAM_ERROR, Map.of("message", errorMsg));
    }

    /**
     * 静态资源找不到（含路径拼错、接口不存在）按 404 返回，不能落到兜底 500 伪装成系统故障。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResultData handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.warn("接口不存在：{}", e.getResourcePath());
        return ResultData.fail(ResultCode.NOT_FOUND, Map.of("message", "请求的接口不存在，请检查地址是否正确"));
    }

    /**
     * 处理404异常（需要在配置中开启：spring.mvc.throw-exception-if-no-handler-found=true）
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResultData handle404Exception(NoHandlerFoundException e) {
        log.warn("404异常：请求路径不存在 - {}", e.getRequestURL());
        return ResultData.fail(ResultCode.NOT_FOUND);
    }

    /**
     * 缺少必填请求参数属于调用方参数问题，按参数错误返回，不能落到兜底 500。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResultData handleMissingParameter(MissingServletRequestParameterException e) {
        String message = "缺少必填参数：" + e.getParameterName();
        log.warn("参数校验异常：{}", message);
        return ResultData.fail(ResultCode.PARAM_ERROR, Map.of("message", message));
    }

    /**
     * 参数类型不匹配（例如路径参数传了非数字、日期格式不对）按参数错误返回，避免前端只看到“操作失败”。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResultData handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "参数格式不正确：" + e.getName();
        log.warn("参数校验异常：{}，期望类型 {}", message,
                e.getRequiredType() == null ? "未知" : e.getRequiredType().getSimpleName());
        return ResultData.fail(ResultCode.PARAM_ERROR, Map.of("message", message));
    }

    /**
     * 请求体无法解析（JSON 语法错误、日期时间格式不合法等）同样按参数错误返回。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResultData handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return ResultData.fail(ResultCode.PARAM_ERROR, Map.of("message", "请求内容格式不正确，请检查字段类型与日期时间格式"));
    }

    /**
     * 请求方法不支持时明确返回 405，而不是伪装成系统异常。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ResultData handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持：{}", e.getMessage());
        return ResultData.fail(ResultCode.PARAM_ERROR, Map.of("message", "该接口不支持当前请求方法"));
    }

    /**
     * 响应已经按文件流写出时，异常无法再改写成 JSON；此处只记录日志，避免异常处理器自身再次抛错。
     */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public void handleMessageNotWritable(HttpMessageNotWritableException e) {
        log.error("响应写出失败（响应可能已提交）：{}", e.getMessage());
    }

    /**
     * AI 服务错误只返回可公开的简短原因，不暴露第三方响应、密钥或调用栈。
     */
    @ExceptionHandler(AiServiceException.class)
    public ResultData handleAiServiceException(AiServiceException e) {
        log.warn("AI服务调用失败：{}", e.getMessage());
        return ResultData.fail(ResultCode.BUSINESS_ERROR, Map.of("message", e.getMessage()));
    }

    /**
     * 唯一约束冲突：说明并发或历史数据导致重复，给前端可读提示而不是笼统的系统异常。
     */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResultData handleDuplicateKey(org.springframework.dao.DuplicateKeyException e,
                                         jakarta.servlet.http.HttpServletRequest request) {
        log.warn("唯一约束冲突：{} {} - {}", request.getMethod(), request.getRequestURI(), e.getMostSpecificCause().getMessage());
        return ResultData.fail(ResultCode.BUSINESS_ERROR,
                Map.of("message", "数据已存在或与既有编号冲突，请刷新后重试"));
    }

    /**
     * 处理所有未捕获的系统异常（兜底处理，优先级最低）
     */
    @ExceptionHandler(Exception.class)
    public ResultData handleSystemException(Exception e, jakarta.servlet.http.HttpServletRequest request) {
        // 系统异常打印error级别日志，包含堆栈信息，方便排查问题
        log.error("系统异常：{} {}", request.getMethod(), request.getRequestURI(), e);
        // 给前端返回通用错误信息，避免暴露系统内部细节
        return ResultData.fail(ResultCode.FAILED,
                Map.of("message", "服务处理异常，请稍后重试；若持续出现请联系管理员"));
    }
}
