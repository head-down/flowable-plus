package io.github.flowable.plus.core.support;

import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BPMN 扩展属性解析工具，从 {@link FlowElement} 中提取自定义 formData。
 *
 * <p>仅提取 http://flowable.org/bpmn 命名空间下的 customProperty 扩展元素，
 * 序列化为 JSON 字符串。无自定义属性时返回 null。</p>
 *
 * @author flowable-plus
 */
public class BpmnFormDataHelper {

    /**
     * 从 BPMN 扩展属性中提取自定义 formData。
     *
     * @param element BPMN 流程元素，不可为 null
     * @return JSON 格式的 formData 字符串，无自定义属性时返回 null
     */
    public String extractFormData(FlowElement element) {
        if (element == null) {
            return null;
        }

        Map<String, List<ExtensionElement>> extElements = element.getExtensionElements();
        if (extElements == null || extElements.isEmpty()) {
            return null;
        }

        List<ExtensionElement> customElements = extElements.get("http://flowable.org/bpmn");
        if (customElements == null || customElements.isEmpty()) {
            return null;
        }

        Map<String, String> properties = new LinkedHashMap<>();
        for (ExtensionElement ext : customElements) {
            if ("customProperty".equals(ext.getName())) {
                Map<String, List<ExtensionAttribute>> attrs = ext.getAttributes();
                if (attrs != null) {
                    String name = getAttributeValue(attrs, "name");
                    String value = getAttributeValue(attrs, "value");
                    if (name != null) {
                        properties.put(name, value);
                    }
                }
            }
        }

        if (properties.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            String val = entry.getValue();
            String escaped = val != null ? val.replace("\\", "\\\\").replace("\"", "\\\"") : "";
            sb.append("\"").append(entry.getKey()).append("\":\"")
                    .append(escaped).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 从 ExtensionAttribute Map 中提取指定属性的字符串值。
     */
    private String getAttributeValue(Map<String, List<ExtensionAttribute>> attrs, String attributeName) {
        List<ExtensionAttribute> attrList = attrs.get(attributeName);
        if (attrList != null && !attrList.isEmpty()) {
            return attrList.get(0).getValue();
        }
        return null;
    }
}
