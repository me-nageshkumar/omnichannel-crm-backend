package me.nageshk.omnichannel.service.chatbot.node;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import org.springframework.stereotype.Component;

/** The executor per node type, from every {@link NodeExecutor} bean. */
@Component
public class NodeExecutors {

    private final Map<NodeType, NodeExecutor> byType = new EnumMap<>(NodeType.class);

    public NodeExecutors(List<NodeExecutor> executors) {
        for (NodeExecutor e : executors) {
            if (byType.put(e.type(), e) != null) throw new IllegalStateException("two executors for " + e.type());
        }
    }

    public NodeExecutor of(NodeType type) {
        NodeExecutor e = byType.get(type);
        if (e == null) throw new IllegalStateException("no executor for " + type);
        return e;
    }
}
