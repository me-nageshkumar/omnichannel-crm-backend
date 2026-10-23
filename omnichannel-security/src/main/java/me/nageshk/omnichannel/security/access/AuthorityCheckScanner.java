package me.nageshk.omnichannel.security.access;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.db.access.AuthorityDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/** Finds the authority codes checked by {@code @PreAuthorize} across the application's beans. */
@Component
public class AuthorityCheckScanner {

    /** GLOBAL is {@code hasAuthority}/{@code hasAnyAuthority}; BRAND is {@code @access.has}. */
    public enum CheckKind { GLOBAL, BRAND }

    public record FoundCheck(String code, Set<CheckKind> kinds, List<String> locations) {}

    private static final String BASE_PACKAGE = "me.nageshk.omnichannel";
    private static final Pattern HAS_AUTHORITY = Pattern.compile("hasAuthority\\(([^)]*)\\)");
    private static final Pattern HAS_ANY_AUTHORITY = Pattern.compile("hasAnyAuthority\\(([^)]*)\\)");
    // Takes the last quoted argument; nested parentheses in the first argument are not supported.
    private static final Pattern ACCESS_HAS = Pattern.compile("@access\\.has\\([^)]*?'([^']*)'\\s*\\)");
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    private final ApplicationContext context;
    private volatile Map<String, FoundCheck> found;

    public AuthorityCheckScanner(ApplicationContext context) {
        this.context = context;
    }

    /** Codes checked in code, sorted by code. Computed on first call. */
    public Map<String, FoundCheck> found() {
        Map<String, FoundCheck> result = found;
        if (result == null) {
            synchronized (this) {
                result = found;
                if (result == null) {
                    result = scan();
                    found = result;
                }
            }
        }
        return result;
    }

    static Map<String, Set<CheckKind>> parse(String expression) {
        Map<String, Set<CheckKind>> codes = new LinkedHashMap<>();
        for (Pattern p : List.of(HAS_AUTHORITY, HAS_ANY_AUTHORITY)) {
            Matcher m = p.matcher(expression);
            while (m.find()) {
                Matcher q = QUOTED.matcher(m.group(1));
                while (q.find()) {
                    add(codes, q.group(1), CheckKind.GLOBAL);
                }
            }
        }
        Matcher m = ACCESS_HAS.matcher(expression);
        while (m.find()) {
            add(codes, m.group(1), CheckKind.BRAND);
        }
        return codes;
    }

    private static void add(Map<String, Set<CheckKind>> codes, String code, CheckKind kind) {
        if (AuthorityDefinition.isValidCode(code)) {
            codes.computeIfAbsent(code, k -> EnumSet.noneOf(CheckKind.class)).add(kind);
        }
    }

    private Map<String, FoundCheck> scan() {
        Map<String, Set<CheckKind>> kinds = new TreeMap<>();
        Map<String, List<String>> locations = new TreeMap<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(type);
            if (!userClass.getName().startsWith(BASE_PACKAGE)) {
                continue;
            }
            record(userClass.getSimpleName(), AnnotatedElementUtils.findMergedAnnotation(userClass, PreAuthorize.class),
                    kinds, locations);
            for (Method method : userClass.getDeclaredMethods()) {
                record(userClass.getSimpleName() + "." + method.getName(),
                        AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class), kinds, locations);
            }
        }
        Map<String, FoundCheck> result = new TreeMap<>();
        kinds.forEach((code, k) -> result.put(code, new FoundCheck(code, k, List.copyOf(locations.get(code)))));
        return Collections.unmodifiableMap(result);
    }

    private static void record(String location, PreAuthorize annotation, Map<String, Set<CheckKind>> kinds,
            Map<String, List<String>> locations) {
        if (annotation == null) {
            return;
        }
        parse(annotation.value()).forEach((code, k) -> {
            kinds.computeIfAbsent(code, c -> EnumSet.noneOf(CheckKind.class)).addAll(k);
            List<String> where = locations.computeIfAbsent(code, c -> new ArrayList<>());
            if (!where.contains(location)) {
                where.add(location);
            }
        });
    }
}
