package com.aegis.mcp.demo.tools;

import com.aegis.mcp.demo.annotation.McpTool;
import com.aegis.mcp.demo.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 工业场景 MCP 工具集（Demo 精简版）。
 *
 * <p>仅 2 个面向业务的核心工具，返回模拟数据：
 * <ol>
 *   <li>{@code query_production_plan} — 查看生产计划与执行进度</li>
 *   <li>{@code query_device_status} — 查看设备实时运行状态</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Component
public class IndustrialTools {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ================================================================
    // 工具 1：查看生产计划
    // ================================================================

    /**
     * 查询指定产线的生产计划与执行进度。
     *
     * <p>返回该产线今日/指定日期的计划产量、实际完成、在制品、良品率等核心指标，
     * 以及计划内的工单列表（工单编号、产品、数量、状态、负责人）。
     *
     * @param lineCode 产线编码，如 LINE-001（总装线）、LINE-002（焊接线），不传返回全部
     * @param date     查询日期，格式 yyyy-MM-dd，默认今天
     * @param status   工单状态筛选：pending(待开工) / in_progress(进行中) / completed(已完成)，不传返回全部
     * @return 生产计划 + 工单列表
     */
    @McpTool(name = "query_production_plan",
             description = "查询产线的生产计划和执行进度，包括计划产量、实际完成、在制品、工单列表等")
    public Map<String, Object> queryProductionPlan(
            @McpToolParam(description = "产线编码，如 LINE-001；不传返回全部产线") String lineCode,
            @McpToolParam(description = "查询日期，格式 yyyy-MM-dd，默认今天") String date,
            @McpToolParam(description = "工单状态筛选: pending/in_progress/completed，不传返回全部") String status) {

        String queryDate = (date != null && !date.isBlank()) ? date : LocalDateTime.now().format(DATE_FMT);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query_date", queryDate);
        result.put("query_time", LocalDateTime.now().format(TIME_FMT));
        result.put("status", "success");

        Random rand = new Random((lineCode != null ? lineCode.hashCode() : 0) + queryDate.hashCode());

        // ---- 产线汇总指标 ----
        List<Map<String, Object>> lineSummaries = new ArrayList<>();
        String[][] lines = {
                {"LINE-001", "总装线", "A车间"},
                {"LINE-002", "焊接线", "B车间"},
                {"LINE-003", "涂装线", "C车间"}
        };

        for (String[] line : lines) {
            if (lineCode != null && !lineCode.isBlank()
                    && !line[0].equalsIgnoreCase(lineCode)) {
                continue;
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("line_code", line[0]);
            summary.put("line_name", line[1]);
            summary.put("workshop", line[2]);
            summary.put("planned_qty", 800 + rand.nextInt(400));
            summary.put("actual_qty", 700 + rand.nextInt(350));
            summary.put("wip_qty", 40 + rand.nextInt(60));
            summary.put("completion_rate", String.format("%.1f%%", 85.0 + rand.nextDouble() * 14));
            summary.put("yield_rate", String.format("%.1f%%", 96.0 + rand.nextDouble() * 3.5));
            summary.put("active_orders", 1 + rand.nextInt(4));
            lineSummaries.add(summary);
        }
        result.put("lines", lineSummaries);

        // ---- 工单列表 ----
        String[] allStatuses = {"pending", "in_progress", "completed"};
        List<String> filteredStatuses;
        if (status != null && !status.isBlank()) {
            filteredStatuses = List.of(status.toLowerCase());
        } else {
            filteredStatuses = Arrays.asList(allStatuses);
        }

        String[][] workOrders = {
                {"WO-20260828-001", "LINE-001", "新能源电池外壳", "500", "completed", "A车间-张工"},
                {"WO-20260828-002", "LINE-001", "电机端盖组件", "300", "in_progress", "A车间-李工"},
                {"WO-20260828-003", "LINE-002", "电池极耳焊接", "800", "in_progress", "B车间-王工"},
                {"WO-20260828-004", "LINE-002", "电连接片焊接", "400", "pending", "B车间-陈工"},
                {"WO-20260828-005", "LINE-003", "电芯表面喷涂", "600", "completed", "C车间-赵工"},
                {"WO-20260828-006", "LINE-003", "模组支架喷涂", "200", "pending", "C车间-刘工"}
        };

        List<Map<String, Object>> orders = new ArrayList<>();
        for (String[] wo : workOrders) {
            if (lineCode != null && !lineCode.isBlank()
                    && !wo[1].equalsIgnoreCase(lineCode)) {
                continue;
            }
            if (!filteredStatuses.contains(wo[4])) {
                continue;
            }

            Map<String, Object> order = new LinkedHashMap<>();
            order.put("order_no", wo[0]);
            order.put("line_code", wo[1]);
            order.put("product_name", wo[2]);
            order.put("planned_qty", Integer.parseInt(wo[3]));
            order.put("status", wo[4]);
            order.put("assignee", wo[5]);

            // 补充进度字段
            switch (wo[4]) {
                case "completed" -> {
                    order.put("actual_qty", Integer.parseInt(wo[3]));
                    order.put("completion_time", queryDate + " " + (14 + rand.nextInt(4)) + ":" + (10 + rand.nextInt(50)));
                }
                case "in_progress" -> {
                    int actual = Integer.parseInt(wo[3]) * (40 + rand.nextInt(50)) / 100;
                    order.put("actual_qty", actual);
                    order.put("progress", actual * 100 / Integer.parseInt(wo[3]) + "%");
                    order.put("estimated_finish", "今日 " + (17 + rand.nextInt(3)) + ":00 左右");
                }
                case "pending" -> {
                    order.put("actual_qty", 0);
                    order.put("progress", "0%");
                    order.put("planned_start", queryDate + " " + (rand.nextInt(2) + 8) + ":00");
                }
            }
            orders.add(order);
        }
        result.put("work_orders", orders);
        result.put("total_orders", orders.size());

        return result;
    }

    // ================================================================
    // 工具 2：查看设备运行状态
    // ================================================================

    /**
     * 查询工厂设备的实时运行状态。
     *
     * <p>返回设备的基本信息（名称/位置/类型）、当前运行状态、
     * 以及状态对应的实时参数（温度/振动/负载/故障码等）。
     *
     * @param deviceCode   设备编码，如 EQ-CNC-001；不传返回全部
     * @param statusFilter 状态筛选：running(运行中) / idle(待机) / maintenance(维护) / fault(故障)
     * @return 设备状态列表
     */
    @McpTool(name = "query_device_status",
             description = "查询工厂设备实时运行状态，包括温度、振动、负载、故障等运行参数")
    public Map<String, Object> queryDeviceStatus(
            @McpToolParam(description = "设备编码，如 EQ-CNC-001；不传返回全部") String deviceCode,
            @McpToolParam(description = "状态筛选: running/idle/maintenance/fault，不传返回全部") String statusFilter) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query_time", LocalDateTime.now().format(TIME_FMT));
        result.put("status", "success");

        Random rand = new Random();

        // 7 台设备 + 加权状态分布
        String[][] deviceTemplates = {
                {"EQ-CNC-001", "数控加工中心 #1",   "CNC",      "LINE-001"},
                {"EQ-CNC-002", "数控加工中心 #2",   "CNC",      "LINE-001"},
                {"EQ-ROBOT-001", "焊接机器人 #1",   "ROBOT",    "LINE-002"},
                {"EQ-ROBOT-002", "焊接机器人 #2",   "ROBOT",    "LINE-002"},
                {"EQ-CONV-001", "输送线 #1",        "CONVEYOR", "LINE-001"},
                {"EQ-PAINT-001", "喷涂机器人 #1",   "PAINT",    "LINE-003"},
                {"EQ-INSPECT-001", "视觉检测仪 #1", "INSPECTION", "LINE-001"}
        };
        String[] statuses = {"running", "idle", "maintenance", "fault"};
        int[] weights = {60, 20, 15, 5};

        List<Map<String, Object>> devices = new ArrayList<>();
        int[] statusCount = new int[4];

        for (String[] tpl : deviceTemplates) {
            if (deviceCode != null && !deviceCode.isBlank()
                    && !tpl[0].equalsIgnoreCase(deviceCode)) {
                continue;
            }

            // 加权随机状态
            int r = rand.nextInt(100);
            String st;
            if      (r < weights[0]) st = statuses[0];
            else if (r < weights[0] + weights[1]) st = statuses[1];
            else if (r < weights[0] + weights[1] + weights[2]) st = statuses[2];
            else                     st = statuses[3];

            if (statusFilter != null && !statusFilter.isBlank()
                    && !st.equalsIgnoreCase(statusFilter)) {
                continue;
            }
            statusCount[switch (st) {
                case "running"     -> 0;
                case "idle"        -> 1;
                case "maintenance" -> 2;
                default            -> 3;
            }]++;

            Map<String, Object> device = new LinkedHashMap<>();
            device.put("device_code", tpl[0]);
            device.put("device_name", tpl[1]);
            device.put("device_type", tpl[2]);
            device.put("location", tpl[3]);
            device.put("status", st);

            Map<String, Object> params = new LinkedHashMap<>();
            switch (st) {
                case "running" -> {
                    params.put("temperature", 35 + rand.nextInt(25) + "°C");
                    params.put("vibration", String.format("%.2f mm/s", 0.5 + rand.nextFloat() * 1.5));
                    params.put("load", 60 + rand.nextInt(40) + "%");
                    params.put("today_run_hours", 6 + rand.nextInt(8) + "h");
                    params.put("current_program", "PROG_" + (100 + rand.nextInt(50)));
                }
                case "idle" -> {
                    params.put("temperature", 25 + rand.nextInt(10) + "°C");
                    params.put("vibration", "0.01 mm/s");
                    params.put("load", "0%");
                    params.put("idle_duration", 10 + rand.nextInt(30) + " min");
                }
                case "maintenance" -> {
                    params.put("temperature", "24°C");
                    params.put("vibration", "0");
                    params.put("maint_type", rand.nextBoolean() ? "预防性维护" : "故障维修");
                    params.put("est_completion", rand.nextInt(120) + " 分钟后");
                }
                case "fault" -> {
                    params.put("temperature", 55 + rand.nextInt(20) + "°C ⚠");
                    params.put("vibration", String.format("%.2f mm/s ⚠", 3.0 + rand.nextFloat() * 2.0));
                    params.put("error_code", "E" + (1000 + rand.nextInt(500)));
                    params.put("error_msg", getRandomFaultMsg());
                    params.put("fault_duration", 5 + rand.nextInt(60) + " min");
                }
            }
            device.put("parameters", params);
            devices.add(device);
        }

        result.put("devices", devices);
        result.put("total_count", devices.size());
        result.put("summary", Map.of(
                "running",     statusCount[0],
                "idle",        statusCount[1],
                "maintenance", statusCount[2],
                "fault",       statusCount[3]
        ));

        return result;
    }

    // ================================================================
    // 辅助
    // ================================================================

    private String getRandomFaultMsg() {
        String[] msgs = {
                "主轴过热保护触发",
                "伺服驱动过流报警",
                "气压异常下降",
                "通讯中断，请检查 EtherCAT 总线",
                "安全光幕触发",
                "急停按钮按下",
                "刀具断裂检测",
                "工件卡料报警"
        };
        return msgs[new Random().nextInt(msgs.length)];
    }
}
