package com.cloudops;

import com.cloudops.entity.MockAlarm;
import com.cloudops.entity.MockBillingStream;
import com.cloudops.mapper.AlarmMapper;
import com.cloudops.mapper.BillingStreamMapper;
import com.cloudops.tool.AlarmQueryTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    private AlarmMapper alarmMapper;

    @Autowired
    private BillingStreamMapper billingStreamMapper;

    @Autowired
    private AlarmQueryTool alarmQueryTool;

    @GetMapping("/test/alarms")
    public List<MockAlarm> testAlarms() {
        return alarmMapper.selectList(null);
    }

    @GetMapping("/test/billing")
    public List<MockBillingStream> testBillingAlarms() {
        return billingStreamMapper.selectList(null);
    }


    @GetMapping("/test/tool/alarm")
    public Object testAlarmTool() {
        return alarmQueryTool.queryAlarms(5);
    }
}
