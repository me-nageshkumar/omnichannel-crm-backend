package me.nageshk.omnichannel.app.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.service.phone.move.NumberRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Every stored record that names a phone number by id is either re-pointed by a move or left as is for a stated
 * reason (spec §7.2 step 3): a new collection keyed by a number fails here until it is classified.
 *
 * <p>A document is found when it holds, at any depth of our own types, a field named {@code phoneNumberId} (or ending
 * in {@code PhoneNumberId}) or a value of a type that stands for a number ({@link #NUMBER_TYPES}, such as
 * {@code EventData.NumberData} under {@code data.number}). What the scan cannot see is listed in
 * {@link NumberRecords} by hand: the campaign sender ids ({@code campaigns.senderIds},
 * {@code campaignRecipients.senderId} and {@code preferredSenderId}), the number inside the webhook JSON of
 * {@code channelWebhookEvents}, and the GridFS metadata of {@code chatMedia.files}.
 */
class NumberRecordsCoverageTest {

    /** Types whose value is a phone number: a field of one of them names a number whatever the field is called. */
    static final Set<Class<?>> NUMBER_TYPES = Set.of(EventData.NumberData.class);

    @Test
    void everyCollectionWithAPhoneNumberIdIsRepointedOrLeftWithAReason() throws Exception {
        Map<String, String> found = numberHolders(documents());

        assertThat(found).as("documents with a phone number id").containsKeys("waWebChats", "waWebMessages",
                "chatbotRuns", "crmContacts", "crmDeals", "connectCodes", "automationEvents",
                "automationEventHistory", "automationTests");
        assertThat(found).containsEntry("automationEvents", "AutomationEvent.data.number");
        assertThat(unclassified(found)).as("neither re-pointed nor left with a reason: %s", found).isEmpty();
        assertThat(NumberRecords.LEFT.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
        assertThat(NumberRecords.REPOINTED.keySet()).doesNotContainAnyElementsOf(NumberRecords.LEFT.keySet());
    }

    @Test
    void aDocumentHoldingNumberDataInAnUnlistedCollectionFailsTheGuard() {
        Map<String, String> found = numberHolders(Set.of(HoldsEventData.class, HoldsNumberDataInAList.class));

        assertThat(found).containsEntry("someNewEvents", "HoldsEventData.data.number")
                .containsEntry("someNewNumberLists", "HoldsNumberDataInAList.numbers");
        assertThat(unclassified(found)).containsExactly("someNewEvents", "someNewNumberLists");
    }

    // not static, so the classpath scan of the real documents never picks these up
    @Document("someNewEvents")
    class HoldsEventData {
        EventData data;
    }

    @Document("someNewNumberLists")
    class HoldsNumberDataInAList {
        java.util.List<EventData.NumberData> numbers;
    }

    private static Map<String, String> numberHolders(Set<Class<?>> documents) {
        TreeMap<String, String> found = new TreeMap<>();
        for (Class<?> type : documents) {
            String path = numberField(type, "", new HashSet<>());
            if (path != null) found.put(collectionOf(type), type.getSimpleName() + "." + path);
        }
        return found;
    }

    private static Set<String> unclassified(Map<String, String> found) {
        Set<String> out = new TreeSet<>(found.keySet());
        out.removeAll(NumberRecords.REPOINTED.keySet());
        out.removeAll(NumberRecords.LEFT.keySet());
        return out;
    }

    private static Set<Class<?>> documents() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Document.class));
        Set<Class<?>> out = new HashSet<>();
        for (BeanDefinition d : scanner.findCandidateComponents("me.nageshk.omnichannel")) {
            out.add(Class.forName(d.getBeanClassName()));
        }
        return out;
    }

    private static String collectionOf(Class<?> type) {
        Document d = type.getAnnotation(Document.class);
        String name = d.collection().isEmpty() ? d.value() : d.collection();
        return name.isEmpty() ? Character.toLowerCase(type.getSimpleName().charAt(0)) + type.getSimpleName().substring(1)
                : name;
    }

    /** The path of the first field that names a number, nested types included; null when there is none. */
    private static String numberField(Class<?> type, String prefix, Set<Class<?>> seen) {
        if (!seen.add(type)) return null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                Set<Class<?>> own = ownTypes(f);
                if (f.getName().equals("phoneNumberId") || f.getName().endsWith("PhoneNumberId")
                        || own.stream().anyMatch(NUMBER_TYPES::contains)) {
                    return prefix + f.getName();
                }
                for (Class<?> nested : own) {
                    String inner = numberField(nested, prefix + f.getName() + ".", seen);
                    if (inner != null) return inner;
                }
            }
        }
        return null;
    }

    /** The field's own type and its type arguments (a {@code List<ChannelChange>}), when they are ours. */
    private static Set<Class<?>> ownTypes(Field f) {
        Set<Class<?>> out = new HashSet<>();
        addIfOwn(f.getType(), out);
        Type generic = f.getGenericType();
        if (generic instanceof ParameterizedType p) {
            for (Type arg : p.getActualTypeArguments()) {
                if (arg instanceof Class<?> c) addIfOwn(c, out);
            }
        }
        return out;
    }

    private static void addIfOwn(Class<?> c, Set<Class<?>> out) {
        if (!c.isPrimitive() && !c.isEnum() && c.getPackageName().startsWith("me.nageshk.omnichannel")) out.add(c);
    }
}
