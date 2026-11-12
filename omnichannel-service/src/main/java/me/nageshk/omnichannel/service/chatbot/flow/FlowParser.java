package me.nageshk.omnichannel.service.chatbot.flow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.ValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the parts of a canvas flow the server needs: {@code nodes[].id}, {@code type}, {@code data.name},
 * {@code data.settings} and {@code edges[].id/source/sourceHandle/target}. Everything else (positions, viewport,
 * selection) is the canvas's and is kept by the caller untouched. A flow the server cannot read is refused with
 * {@code fields.flow}; settings of the wrong shape with {@code fields.nodes.<id>.settings}.
 */
@Component
public class FlowParser {

    private final ObjectMapper mapper;

    public FlowParser(ObjectMapper mapper) {
        this.mapper = mapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    public Flow parse(Map<String, Object> flow) {
        JsonNode root = mapper.valueToTree(flow == null ? Map.of() : flow);
        JsonNode nodes = root.get("nodes");
        if (nodes == null || !nodes.isArray()) throw refuse("needs a nodes array");
        List<FlowNode> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode n : nodes) {
            String id = text(n.get("id"));
            if (id == null || id.isBlank()) throw refuse("a node has no id");
            if (!ids.add(id)) throw refuse("two nodes share the id " + id);
            String typeCode = text(n.get("type"));
            NodeType type = NodeType.of(typeCode == null ? "" : typeCode)
                    .orElseThrow(() -> refuse("unknown node type " + typeCode + " on node " + id));
            JsonNode data = n.get("data");
            String name = data == null ? null : text(data.get("name"));
            JsonNode settings = data == null || data.get("settings") == null ? mapper.createObjectNode() : data.get("settings");
            out.add(new FlowNode(id, name, type, settings));
        }
        List<FlowEdge> edges = new ArrayList<>();
        JsonNode edgeNodes = root.get("edges");
        if (edgeNodes != null && edgeNodes.isArray()) {
            for (JsonNode e : edgeNodes) {
                String source = text(e.get("source"));
                String target = text(e.get("target"));
                if (source == null || !ids.contains(source)) throw refuse("an edge leaves an unknown node " + source);
                if (target == null || !ids.contains(target)) throw refuse("an edge reaches an unknown node " + target);
                String handle = text(e.get("sourceHandle"));
                edges.add(new FlowEdge(text(e.get("id")), source, handle == null || handle.isBlank() ? Outputs.NEXT : handle,
                        target));
            }
        }
        return new Flow(List.copyOf(out), List.copyOf(edges));
    }

    /** The node's settings as the given record; unknown keys (the console's own) are ignored. */
    public <T> T settings(FlowNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node.settings(), type);
        } catch (DatabindException e) {
            throw new ValidationException("nodes." + node.id() + ".settings", "is not valid: " + e.getOriginalMessage());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("nodes." + node.id() + ".settings", "is not valid: " + e.getMessage());
        }
    }

    private static ValidationException refuse(String why) {
        return new ValidationException("flow", why);
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
    }
}
