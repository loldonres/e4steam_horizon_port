package link.e4steam.internal.kaleido.lib.quiltconfig.impl;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.Alias;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.ConfigFieldAnnotationProcessor;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataContainerBuilder;
import org.jetbrains.annotations.ApiStatus.Internal;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Internal
public @interface Aliases {
   Alias[] value();

   public static final class Processor implements ConfigFieldAnnotationProcessor<Aliases> {
      public void process(Aliases aliases, MetadataContainerBuilder<?> builder) {
         for (Alias alias : aliases.value()) {
            for (String c : alias.value()) {
               builder.metadata(Alias.TYPE, b -> b.add(c));
            }
         }
      }
   }
}
