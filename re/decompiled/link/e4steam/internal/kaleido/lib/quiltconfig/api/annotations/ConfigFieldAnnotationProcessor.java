package link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations;

import java.lang.annotation.Annotation;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataContainerBuilder;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.ConfigFieldAnnotationProcessors;

public interface ConfigFieldAnnotationProcessor<T extends Annotation> {
   void process(T var1, MetadataContainerBuilder<?> var2);

   static <T extends Annotation> void register(Class<T> annotationClass, ConfigFieldAnnotationProcessor<T> processor) {
      ConfigFieldAnnotationProcessors.register(annotationClass, processor);
   }

   static void applyAnnotationProcessors(Annotation annotation, MetadataContainerBuilder<?> builder) {
      ConfigFieldAnnotationProcessors.applyAnnotationProcessors(annotation, builder);
   }
}
