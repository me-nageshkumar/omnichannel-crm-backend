package me.nageshk.omnichannel.crm.deal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * {@link LaneLocks} for one JVM: one {@link ReentrantLock} per lane key, taken in sorted key order so two callers
 * locking the same lanes cannot deadlock. Locks are kept for the life of the process (one per lane ever touched, a
 * small bounded set). Not shared between instances: see {@link LaneLocks}.
 */
@Component
class InJvmLaneLocks implements LaneLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T withLanes(String workspaceId, Collection<Lane> lanes, Supplier<T> work) {
        TreeSet<String> keys = new TreeSet<>();
        for (Lane l : lanes) keys.add(workspaceId + ":" + l.pipelineId() + ":" + l.stageId());
        List<ReentrantLock> held = new ArrayList<>(keys.size());
        try {
            for (String key : keys) {
                ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
                lock.lock();
                held.add(lock);
            }
            return work.get();
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) held.get(i).unlock();
        }
    }
}
