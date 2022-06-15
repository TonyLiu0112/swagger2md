package org.example;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static java.util.Optional.ofNullable;
import static org.example.FileTemplate.*;
import static org.example.SwaggerKey.*;

public class App {

    private static String swaggerDocUrl = "http://mirach-api-koderover-dev-open-store.api-ingress.sandload.cn/v2/api-docs?group=default-全部";
    private static String output_root_dir = "/Users/tony/Downloads/swagger2md";

    private static final Map<String, String> tagDirMap = new HashMap<>();
    private static final Set<String> refDistinctSet = new HashSet<>();

    public static void main(String[] args) {
        if (args == null || args.length != 2) {
            System.err.println("输入参数有误, example: java -jar swagger2md.jar [your swagger json url] [out put path]");
            return;
        }
        swaggerDocUrl = args[0];
        output_root_dir = args[1];

        JSONObject jsonObject = getDocJson();
        JSONObject paths = jsonObject.getJSONObject(PATHS);

        initDirByTags(paths);

        paths.forEach((url, o) -> {
            List<String> fileLines = new ArrayList<>();

            JSONObject detail = (JSONObject) o;
            JSONObject post = detail.getJSONObject(POST);

            // 写入标题
            writeTitle(fileLines, post);

            // 写入API描述
            writeApiDesc(url, fileLines);

            // 写入请求参数
            refDistinctSet.clear();
            writeRequestTable(jsonObject, fileLines, post);

            // 写入响应参数
            refDistinctSet.clear();
            writeResponseTable(jsonObject, fileLines, post);

            // 写入示例
            writeExample(fileLines, jsonObject, post);

            // 写入文件流
            String tag = post.getJSONArray(TAGS).getString(0);
            File file = new File(tagDirMap.get(tag) + File.separator + post.getString(SUMMARY) + ".md");
            writeFile(file, fileLines);

        });

    }

    private static void writeExample(List<String> lines, JSONObject jsonObject, JSONObject post) {
        JSONObject reqBody = new JSONObject();
        JSONObject respBody;

        JSONArray parameters = post.getJSONArray(PARAMETERS);
        if (parameters.size() == 1) {
            String refStr = parameters.getJSONObject(0).getJSONObject(SCHEMA).getString($REF);
            reqBody = buildExampleDataByRef(jsonObject, refStr);
        }

        String respRef = post.getJSONObject(RESPONSES).getJSONObject("200").getJSONObject(SCHEMA).getString($REF);
        respBody = buildExampleDataByRef(jsonObject, respRef);
        setDefaultResult(respBody);

        lines.add(file_example_title);
        lines.add(file_empty_line);
        // req
        lines.add(file_example_req);
        lines.add(file_code_warp + "json");
        lines.add(JSON.toJSONString(reqBody, true));
        lines.add(file_code_warp);

        lines.add(file_empty_line);
        // resp
        lines.add(file_example_resp);
        lines.add(file_code_warp + "json");
        lines.add(JSON.toJSONString(respBody, true));
        lines.add(file_code_warp);
    }

    private static void setDefaultResult(JSONObject respBody) {
        if (respBody.containsKey("message")) {
            respBody.put("message", "成功");
        }
        if (respBody.containsKey("statusCode")) {
            respBody.put("statusCode", "100");
        }
        if (respBody.containsKey("status")) {
            respBody.put("status", true);
        }
    }

    private static void writeResponseTable(JSONObject jsonObject, List<String> lines, JSONObject post) {
        lines.add(file_tab_resp_desc);
        lines.add(file_empty_line);
        String respRef = post.getJSONObject(RESPONSES).getJSONObject("200").getJSONObject(SCHEMA).getString($REF);
        lines.addAll(buildTablesByRef(jsonObject, respRef));
    }

    private static void writeRequestTable(JSONObject jsonObject, List<String> lines, JSONObject post) {
        lines.add(file_tab_req_desc);
        lines.add(file_empty_line);
        JSONArray parameters = post.getJSONArray(PARAMETERS);
        if (parameters.size() == 1) {
            // body
            String refStr = parameters.getJSONObject(0).getJSONObject(SCHEMA).getString($REF);
            lines.addAll(buildTablesByRef(jsonObject, refStr));
        } else {
            // query string
            lines.addAll(getQueryParamsTables(jsonObject, parameters, post));
        }
        lines.add(file_empty_line);
    }

    private static void writeApiDesc(String url, List<String> lines) {
        lines.add(file_api_path_title);
        lines.add(file_code_warp);
        lines.add("POST " + url);
        lines.add(file_code_warp);
        lines.add(file_empty_line);
    }

    private static void writeTitle(List<String> lines, JSONObject post) {
        lines.add(String.format(file_title, post.getString(SUMMARY)));
        lines.add(file_empty_line);
    }

    private static void initDirByTags(JSONObject paths) {
        paths.forEach((url, o) -> {
            JSONObject detail = (JSONObject) o;
            String tag = detail.getJSONObject(POST).getJSONArray(TAGS).getString(0);
            String rootPath = url.split("/")[1];
            String segment = url.split("/")[2];
            tagDirMap.putIfAbsent(tag, output_root_dir + File.separator + rootPath + File.separator + segment + File.separator);
        });
    }

    private static List<String> getQueryParamsTables(JSONObject jsonObject, JSONArray parameters, JSONObject post) {
        List<String> lines = new ArrayList<>();
        lines.add(tab_head);
        lines.add(tab_line_first);
        for (int i = 0; i < parameters.size(); i++) {
            JSONObject fieldObject = parameters.getJSONObject(i);
            String field = fieldObject.getString(NAME);
            String type = fieldObject.getString(TYPE);
            Boolean required = fieldObject.getBoolean(REQUIRED);
            String example = fieldObject.getString(X_EXAMPLE);
            String description = fieldObject.getString(DESCRIPTION);
            lines.add(String.format(tab_line_temp, field, description, required ? "是" : "否", type, "", "", example));
        }
        return lines;
    }

    private static List<String> buildTablesByRef(JSONObject jsonObject, String reqBodyRef) {
        if (refDistinctSet.contains(reqBodyRef)) {
            return Lists.newArrayList();
        }
        refDistinctSet.add(reqBodyRef);
        reqBodyRef = reqBodyRef.replace("#/", "");

        JSONObject jsonProperty = jsonObject;
        for (String field : reqBodyRef.split("/")) {
            jsonProperty = jsonProperty.getJSONObject(field);
        }

        JSONArray requiredList = jsonProperty.getJSONArray(REQUIRED);
        JSONObject properties = jsonProperty.getJSONObject(PROPERTIES);

        List<String> lines = new ArrayList<>();
        List<String> subLines = new ArrayList<>();
        lines.add(tab_head);
        lines.add(tab_line_first);

        properties.forEach((field, descObj) -> {
            JSONObject descJsonObj = JSON.parseObject(JSON.toJSONString(descObj));

            // 字段描述集
            String description = StringUtils.isEmpty(descJsonObj.getString(DESCRIPTION)) ? "" : descJsonObj.getString(DESCRIPTION);
            String fieldType = descJsonObj.getString(TYPE);
            String example = StringUtils.isEmpty(descJsonObj.getString(EXAMPLE)) ? "" : descJsonObj.getString(EXAMPLE);
            Integer minimum = descJsonObj.getInteger(MINIMUM);
            Integer maximum = descJsonObj.getInteger(MAXIMUM);
            Boolean exclusiveMinimum = ofNullable(descJsonObj.getBoolean(EXCLUSIVE_MINIMUM)).orElse(false);
            Boolean exclusiveMaximum = ofNullable(descJsonObj.getBoolean(EXCLUSIVE_MAXIMUM)).orElse(false);

            LineMeta lineMeta = new LineMeta();

            if (StringUtils.isEmpty(fieldType)) {
                fieldType = "object";
                String subRef = descJsonObj.getString($REF);
                if (StringUtils.isNotEmpty(subRef)) {
                    processSubRef(jsonObject, subLines, lineMeta, subRef);
                } else {
                    processLineMeta(descJsonObj, minimum, maximum, exclusiveMinimum, exclusiveMaximum, lineMeta);
                    lineMeta.mayBeType = descJsonObj.getJSONObject(ITEMS).getString(TYPE);
                }
            } else if (StringUtils.equals(ARRAY, fieldType)) {
                String subRef = descJsonObj.getJSONObject(ITEMS).getString(TYPE);
                if (StringUtils.isNotEmpty(subRef) && subRef.startsWith("#")) {
                    processSubRef(jsonObject, subLines, lineMeta, subRef);
                } else {
                    JSONObject items = descJsonObj.getJSONObject(ITEMS);
                    processLineMeta(items, minimum, maximum, exclusiveMinimum, exclusiveMaximum, lineMeta);
                    lineMeta.mayBeType = items.getString(TYPE);
                }
            } else {
                processLineMeta(descJsonObj, minimum, maximum, exclusiveMinimum, exclusiveMaximum, lineMeta);
            }

            lines.add(String.format(tab_line_temp,
                    field,
                    description,
                    isRequired(field, requiredList),
                    fieldType,
                    lineMeta.mayBeType,
                    lineMeta.limitDesc,
                    example));
        });
        lines.addAll(subLines);
        return lines;
    }

    private static void processSubRef(JSONObject jsonObject, List<String> subLines, LineMeta lineMeta, String subRef) {
        lineMeta.mayBeType = subRef.substring(subRef.lastIndexOf("/") + 1);
        subLines.add("### " + lineMeta.mayBeType);
        List<String> subTabs = buildTablesByRef(jsonObject, subRef);
        if (CollectionUtils.isEmpty(subTabs)) {
            subLines.remove(subLines.size() - 1);
        } else {
            subLines.addAll(subTabs);
        }
    }

    private static void processLineMeta(JSONObject descJsonObj, Integer minimum, Integer maximum, Boolean exclusiveMinimum, Boolean exclusiveMaximum, LineMeta lineMeta) {
        String enums = descJsonObj.getString(ENUMS);
        if (StringUtils.isNotEmpty(enums)) {
            lineMeta.limitDesc = "枚举: " + enums;
        }
        if (minimum != null) {
            if (!exclusiveMinimum) {
                lineMeta.limitDesc = minimum + " =";
                lineMeta.limitDesc += "< value";
            } else {
                lineMeta.limitDesc = minimum + " < value";
            }
        }
        if (maximum != null) {
            lineMeta.limitDesc += " <";
            if (!exclusiveMaximum) {
                lineMeta.limitDesc += "=";
            }
            lineMeta.limitDesc += " " + maximum;
        }
    }

    private static JSONObject buildExampleDataByRef(JSONObject jsonObject, String reqBodyRef) {
        reqBodyRef = reqBodyRef.replace("#/", "");

        JSONObject jsonProperty = jsonObject;
        for (String field : reqBodyRef.split("/")) {
            jsonProperty = jsonProperty.getJSONObject(field);
        }

        JSONObject properties = jsonProperty.getJSONObject(PROPERTIES);
        JSONObject rootJSONObject = new JSONObject();

        properties.forEach((field, descObj) -> {
            JSONObject descJsonObj = JSON.parseObject(JSON.toJSONString(descObj));
            String fieldType = descJsonObj.getString(TYPE);
            String example = StringUtils.isEmpty(descJsonObj.getString(EXAMPLE)) ? "" : descJsonObj.getString(EXAMPLE);
            if (StringUtils.isEmpty(fieldType)) {
                String subRef = descJsonObj.getString($REF);
                if (StringUtils.isNotEmpty(subRef)) {
                    rootJSONObject.put(field, buildExampleDataByRef(jsonObject, subRef));
                } else {
                    rootJSONObject.put(field, example);
                }
            } else if (StringUtils.equals(ARRAY, fieldType)) {
                String subRef = descJsonObj.getJSONObject(ITEMS).getString($REF);
                if (StringUtils.isNotEmpty(subRef)) {
                    JSONObject arraySignObj = buildExampleDataByRef(jsonObject, subRef);
                    rootJSONObject.put(field, JSON.parseArray("[" + JSON.toJSONString(arraySignObj) + "]"));
                } else {
                    rootJSONObject.put(field, JSON.parseArray(StringUtils.isEmpty(example) ? "[]" : example));
                }
            } else {
                rootJSONObject.put(field, buildExampleWithType(example, fieldType));
            }
        });
        return rootJSONObject;
    }

    private static Object buildExampleWithType(String example, String fieldType) {
        if (StringUtils.isEmpty(fieldType) || STRING.equals(fieldType)) {
            return example;
        }
        if (INTEGER.equals(fieldType) || LONG_TYPE.equals(fieldType)) {
            if (StringUtils.isEmpty(example)) {
                return 0;
            }
            return Integer.parseInt(example);
        }
        if (BOOLEAN_TYPE.equals(fieldType)) {
            if (StringUtils.isEmpty(example)) {
                return false;
            }
            return Boolean.parseBoolean(example);
        }
        return example;
    }

    private static String isRequired(String field, JSONArray required) {
        if (required == null || required.size() == 0) {
            return "否";
        }
        boolean match = required.stream().anyMatch(o -> StringUtils.equals(field, o.toString()));
        return match ? "是" : "否";
    }

    private static void writeFile(File file, List<String> lines) {
        try {
            FileUtils.writeLines(file, lines);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static JSONObject getDocJson() {
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> forEntity = restTemplate.getForEntity(swaggerDocUrl, String.class);
        return JSON.parseObject(forEntity.getBody());
    }

    private static class LineMeta {
        String mayBeType = "";
        String limitDesc = "";
    }

}
