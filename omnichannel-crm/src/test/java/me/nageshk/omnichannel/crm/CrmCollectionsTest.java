package me.nageshk.omnichannel.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.mapping.Document;

/** Structural half of the isolation proof: the CRM stores nothing outside {@code crm*} collections. */
class CrmCollectionsTest {

    @Test
    void everyCrmDocumentMapsToACrmCollection() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Document.class));
        Set<String> collections = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("me.nageshk.omnichannel.crm")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            Document doc = type.getAnnotation(Document.class);
            String name = doc.collection().isEmpty() ? doc.value() : doc.collection();
            assertThat(name).as("collection of " + type.getName()).startsWith("crm");
            collections.add(name);
        }
        assertThat(collections).contains("crmContacts", "crmDeals", "crmActivities");
    }
}
