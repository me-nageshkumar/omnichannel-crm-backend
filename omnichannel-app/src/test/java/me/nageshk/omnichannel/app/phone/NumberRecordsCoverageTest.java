package me.nageshk.omnichannel.app.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import me.nageshk.omnichannel.service.phone.move.NumberRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Every stored record that names a phone number by id is either re-pointed by a move or left as is for a stated
 * reason (spec §7.2 step 3): a new collection keyed by a number fails here until it is classified.
 */
class NumberRecordsCoverageTest {

    @Test
    void everyCollectionWithAPhoneNumberIdIsRepointedOrLeftWithAReason() throws Exception {
        TreeMap<String, String> found = new TreeMap<>();
        for (Class<?> type : documents()) {
            String path = numberField(type, "", new HashSet<>());
            if (path != null) found.put(collectionOf(type), type.getSimpleName() + "." + path);
        }

        assertThat(found).as("documents with a phone number id").containsKeys("waWebChats", "waWebMessages",
                "chatbotRuns", "crmContacts", "crmDeals", "connectCodes");
        Set<String> unclassified = new TreeSet<>(found.keySet());
        unclassified.removeAll(NumberRecords.REPOINTED.keySet());
        unclassified.removeAll(NumberRecords.LEFT.keySet());
        assertThat(unclassified).as("neither re-pointed nor left with a reason: %s", found).isEmpty();
        assertThat(NumberRecords.LEFT.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
        assertThat(NumberRecords.REPOINTED.keySet()).doesNotContainAnyElementsOf(NumberRecords.LEFT.keySet());
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

    /** The first field named {@code phoneNumberId} (or ending in {@code PhoneNumberId}), nested types included. */
    private static String numberField(Class<?> type, String prefix, Set<Class<?>> seen) {
        if (!seen.add(type)) return null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getName().equals("phoneNumberId") || f.getName().endsWith("PhoneNumberId")) {
                    return prefix + f.getName();
                }
                for (Class<?> nested : ownTypes(f)) {
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
