package btw.forge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One-time-per-class dumper for FC entity AI task lists. Used by all
 * proxy entity types to log which AI tasks each FC entity registered
 * during construction. Output is logged once per FC class to avoid spam.
 */
final class ProxyMobTaskDumper {

    private static final Logger LOGGER = LogManager.getLogger("BTW-AI-DUMP");
    // Concurrent: proxies build FC entities on the client render thread and the server thread.
    private static final java.util.Set<String> DUMPED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private ProxyMobTaskDumper() {}

    static void dumpFcTasks(btw.modern.EntityLiving fc) {
        if (fc == null || !LOGGER.isDebugEnabled()) return;
        String key = fc.getClass().getName();
        if (!DUMPED.add(key)) return;
        StringBuilder sb = new StringBuilder("[AI-DUMP] ").append(key);
        try {
            sb.append(" tasks=[");
            appendTaskList(sb, fc.tasks);
            sb.append("] targetTasks=[");
            appendTaskList(sb, fc.targetTasks);
            sb.append("]");
            LOGGER.debug(sb.toString());
        } catch (Throwable t) {
            LOGGER.debug("[AI-DUMP] failed to dump tasks for {}: {}", key, t.getMessage());
        }
    }

    private static void appendTaskList(StringBuilder sb, btw.modern.EntityAITasks tl) {
        if (tl == null) {
            sb.append("NULL");
            return;
        }
        java.util.List<btw.modern.EntityAITasks.EntityAITaskEntry> entries = tl.taskEntries();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            var e = entries.get(i);
            sb.append("p").append(e.priority).append(":").append(e.action.getClass().getSimpleName());
        }
    }
}
