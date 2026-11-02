package com.neuedu.ems.exception;

/**
 * 任务步骤需要人补数据才能继续时抛出的信号。
 *
 * <p>和普通失败的区别：失败说明这条路走不通，等补充说明流程没错、只是缺人填的东西
 * （例如逐件全检缺实测值）。两种情况在任务实例上要落成不同状态，页面给人的提示也不一样：
 * 失败要人去修配置或单据，等补充只要人去把缺的结果填上。</p>
 */
public class AiTaskWaitingInputException extends RuntimeException {

    public AiTaskWaitingInputException(String message) {
        super(message);
    }
}
