package com.neuedu.ems.service.sys;

import com.neuedu.ems.mapper.sys.BusinessNumberMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 客户前缀-单据类型-年月日-当日流水；取号和读回处于同一事务，跨进程也不重号。 */
@Service
@Transactional
public class BusinessNumberService {
    private static final Set<String> TYPES=Set.of("SO","PO","PU","PL","WO","DO","TK","WR","MI","SI","OUT","IN","RT","QI","NC","ST","RP","SC","LP");
    private final BusinessNumberMapper mapper;
    private final Clock clock;
    @Autowired
    public BusinessNumberService(BusinessNumberMapper mapper) {
        this(mapper,Clock.system(ZoneId.of("Asia/Shanghai")));
    }
    BusinessNumberService(BusinessNumberMapper mapper,Clock clock) {this.mapper=mapper;this.clock=clock;}

    public String next(String type,Long customerId) {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("未知单据类型："+type);
        String prefix=customerId==null?"SYS":mapper.customerPrefix(customerId);
        if(prefix==null || prefix.isBlank()) prefix=customerId==null?"SYS":defaultPrefix(customerId);
        if(!prefix.matches("[A-Z][A-Z0-9]{0,7}")) throw new IllegalStateException("客户编号前缀格式不正确，请先维护客户资料");
        LocalDate today=LocalDate.now(clock);
        mapper.increment(prefix,type,today);
        long value=mapper.current(prefix,type,today);
        return prefix+"-"+type+"-"+today.format(DateTimeFormatter.BASIC_ISO_DATE)+"-"+String.format(Locale.ROOT,"%03d",value);
    }
    public String nextFor(String type,String source,Long id) {
        return next(type,id==null?null:mapper.customerId(source,id));
    }
    public static String defaultPrefix(Long customerId) {return "C"+Long.toString(customerId,36).toUpperCase(Locale.ROOT);}

    @Transactional(readOnly=true)
    public String canonical(String number) {
        if(number==null)return null;
        List<String> matches=mapper.currentNumbers(number);
        return matches.size()==1?matches.getFirst():number;
    }
    @Transactional(readOnly=true)
    public List<Long> aliasIds(String type,String keyword) {
        return keyword==null || keyword.isBlank()?List.of():mapper.aliasIds(type,keyword);
    }
}
