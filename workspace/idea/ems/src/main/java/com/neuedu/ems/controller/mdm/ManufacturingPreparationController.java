package com.neuedu.ems.controller.mdm;

import com.neuedu.ems.common.*;
import com.neuedu.ems.service.mdm.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/mdm")
public class ManufacturingPreparationController {
    private final ManufacturingPreparationService preparation;
    private final ProductParameterService parameters;
    public ManufacturingPreparationController(ManufacturingPreparationService preparation,ProductParameterService parameters){
        this.preparation=preparation;this.parameters=parameters;
    }
    @GetMapping("/manufacturing-preparation/products")
    public ResultData products(){return ResultData.success(ResultCode.SUCCESS,preparation.products());}
    @GetMapping("/manufacturing-preparation/{productId}")
    public ResultData inspect(@PathVariable Long productId,@RequestParam(required=false) LocalDate asOf){
        return ResultData.success(ResultCode.SUCCESS,preparation.inspect(productId,asOf));
    }
    @PutMapping("/product-parameter/{id}")
    public ResultData edit(@PathVariable Long id,@RequestBody ProductParameterService.Edit request,@RequestAttribute("userId") Long actor){
        return ResultData.success(ResultCode.SUCCESS,parameters.edit(id,request,actor));
    }
    @PostMapping("/product-parameter/{id}/confirm")
    public ResultData confirm(@PathVariable Long id,@RequestBody ProductParameterService.Review request,@RequestAttribute("userId") Long actor){
        return ResultData.success(ResultCode.SUCCESS,parameters.review(id,request,actor,true));
    }
    @PostMapping("/product-parameter/{id}/reopen")
    public ResultData reopen(@PathVariable Long id,@RequestBody ProductParameterService.Review request,@RequestAttribute("userId") Long actor){
        return ResultData.success(ResultCode.SUCCESS,parameters.review(id,request,actor,false));
    }
}
