package com.neuedu.ems.controller.ai;

import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.controller.sys.FaceAuthController;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Order(0)
@RestControllerAdvice(assignableTypes={PrivateAssistantController.class, FaceAuthController.class})
public class PrivateAssistantErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ResultData> handle(ResponseStatusException error) {
        ResultData result=ResultData.fail(ResultCode.FAILED);
        result.put("code",error.getStatusCode().value());
        result.put("msg",error.getReason()==null ? "请求未完成，请重试" : error.getReason());
        return ResponseEntity.status(error.getStatusCode()).body(result);
    }
}
