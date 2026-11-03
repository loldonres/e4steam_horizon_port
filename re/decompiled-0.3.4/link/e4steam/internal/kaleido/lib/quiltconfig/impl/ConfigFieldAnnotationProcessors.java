package link.e4steam.internal.kaleido.lib.quiltconfig.impl;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Constraint;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.Alias;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.ChangeWarning;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.Comment;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.ConfigFieldAnnotationProcessor;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.DisplayName;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.DisplayNameConvention;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.FloatRange;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.IntegerRange;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.Matches;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.SerializedName;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.SerializedNameConvention;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions.ConfigFieldException;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataContainerBuilder;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.CompoundConfigValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.util.NamingSchemeHelper;

public final class ConfigFieldAnnotationProcessors {
   private static final Map<Class<? extends Annotation>, List<ConfigFieldAnnotationProcessor<?>>> PROCESSORS = new HashMap<>();

   public static <T extends Annotation> void register(Class<T> annotationClass, ConfigFieldAnnotationProcessor<T> processor) {
      PROCESSORS.computeIfAbsent(annotationClass, c -> new ArrayList<>()).add(processor);
   }

   private static <T extends Annotation> void process(ConfigFieldAnnotationProcessor<T> processor, T annotation, MetadataContainerBuilder<?> builder) {
      processor.process(annotation, builder);
   }

   public static void applyAnnotationProcessors(Annotation annotation, MetadataContainerBuilder<?> builder) {
      for (ConfigFieldAnnotationProcessor<?> processor : PROCESSORS.getOrDefault(annotation.annotationType(), Collections.emptyList())) {
         process(processor, annotation, builder);
      }
   }

   static {
      register(Comment.class, new ConfigFieldAnnotationProcessors.CommentProcessor());
      register(Comments.class, new Comments.Processor());
      register(Alias.class, new ConfigFieldAnnotationProcessors.AliasProcessor());
      register(Aliases.class, new Aliases.Processor());
      register(IntegerRange.class, new ConfigFieldAnnotationProcessors.IntegerRangeProcessor());
      register(FloatRange.class, new ConfigFieldAnnotationProcessors.FloatRangeProcessor());
      register(Matches.class, new ConfigFieldAnnotationProcessors.MatchesProcessor());
      register(SerializedName.class, new ConfigFieldAnnotationProcessors.SerialNameProcessor());
      register(SerializedNameConvention.class, new ConfigFieldAnnotationProcessors.SerializedNameConventionProcessor());
      register(DisplayName.class, new ConfigFieldAnnotationProcessors.DisplayNameProcessor());
      register(DisplayNameConvention.class, new ConfigFieldAnnotationProcessors.DisplayNameConventionProcessor());
      register(ChangeWarning.class, new ConfigFieldAnnotationProcessors.ChangeWarningProcessor());
   }

   private static final class AliasProcessor implements ConfigFieldAnnotationProcessor<Alias> {
      private AliasProcessor() {
      }

      public void process(Alias alias, MetadataContainerBuilder<?> builder) {
         for (String c : alias.value()) {
            builder.metadata(Alias.TYPE, aliases -> aliases.add(c));
         }
      }
   }

   private static final class ChangeWarningProcessor implements ConfigFieldAnnotationProcessor<ChangeWarning> {
      private ChangeWarningProcessor() {
      }

      public void process(ChangeWarning name, MetadataContainerBuilder<?> builder) {
         builder.metadata(ChangeWarning.TYPE, nameBuilder -> {
            nameBuilder.setMessage(name.customMessage());
            nameBuilder.setType(name.value());
         });
      }
   }

   private static final class CommentProcessor implements ConfigFieldAnnotationProcessor<Comment> {
      private CommentProcessor() {
      }

      public void process(Comment comment, MetadataContainerBuilder<?> builder) {
         for (String c : comment.value()) {
            builder.metadata(Comment.TYPE, comments -> comments.add(c));
         }
      }
   }

   private static final class DisplayNameConventionProcessor implements ConfigFieldAnnotationProcessor<DisplayNameConvention> {
      private final NamingSchemeHelper namingSchemeHelper = new NamingSchemeHelper();

      private DisplayNameConventionProcessor() {
      }

      public void process(DisplayNameConvention annotation, MetadataContainerBuilder<?> builder) {
         builder.metadata(
            DisplayNameConvention.TYPE,
            nameConventionBuilder -> nameConventionBuilder.set(this.namingSchemeHelper.getNamingScheme(annotation, ConfigFieldException::new))
         );
      }
   }

   private static final class DisplayNameProcessor implements ConfigFieldAnnotationProcessor<DisplayName> {
      private DisplayNameProcessor() {
      }

      public void process(DisplayName name, MetadataContainerBuilder<?> builder) {
         builder.metadata(DisplayName.TYPE, nameBuilder -> {
            nameBuilder.setName(name.value());
            nameBuilder.setTranslatable(name.translatable());
         });
      }
   }

   private static final class FloatRangeProcessor implements ConfigFieldAnnotationProcessor<FloatRange> {
      private FloatRangeProcessor() {
      }

      public void process(FloatRange range, MetadataContainerBuilder<?> builder) {
         if (builder instanceof TrackedValue.Builder) {
            Object defaultValue = ((TrackedValue.Builder)builder).getDefaultValue();
            if (defaultValue instanceof Float) {
               ((TrackedValue.Builder)builder).constraint(Constraint.range((float)range.min(), (float)range.max()));
            } else if (defaultValue instanceof Double) {
               ((TrackedValue.Builder)builder).constraint(Constraint.range(range.min(), range.max()));
            } else if (defaultValue instanceof CompoundConfigValue && Float.class.isAssignableFrom(((CompoundConfigValue)defaultValue).getType())) {
               ((TrackedValue.Builder)builder).constraint(Constraint.all(Constraint.range((float)range.min(), (float)range.max())));
            } else {
               if (!(defaultValue instanceof CompoundConfigValue) || !Double.class.isAssignableFrom(((CompoundConfigValue)defaultValue).getType())) {
                  throw new ConfigFieldException("Constraint FloatRange not applicable for type '" + defaultValue.getClass() + "'");
               }

               ((TrackedValue.Builder)builder).constraint(Constraint.all(Constraint.range(range.min(), range.max())));
            }
         }
      }
   }

   private static final class IntegerRangeProcessor implements ConfigFieldAnnotationProcessor<IntegerRange> {
      private IntegerRangeProcessor() {
      }

      public void process(IntegerRange range, MetadataContainerBuilder<?> builder) {
         if (builder instanceof TrackedValue.Builder) {
            Object defaultValue = ((TrackedValue.Builder)builder).getDefaultValue();
            if (defaultValue instanceof Integer) {
               ((TrackedValue.Builder)builder).constraint(Constraint.range((int)range.min(), (int)range.max()));
            } else if (defaultValue instanceof Long) {
               ((TrackedValue.Builder)builder).constraint(Constraint.range(range.min(), range.max()));
            } else if (defaultValue instanceof CompoundConfigValue && Integer.class.isAssignableFrom(((CompoundConfigValue)defaultValue).getType())) {
               ((TrackedValue.Builder)builder).constraint(Constraint.all(Constraint.range((int)range.min(), (int)range.max())));
            } else {
               if (!(defaultValue instanceof CompoundConfigValue) || !Long.class.isAssignableFrom(((CompoundConfigValue)defaultValue).getType())) {
                  throw new ConfigFieldException("Constraint LongRange not applicable for type '" + defaultValue.getClass() + "'");
               }

               ((TrackedValue.Builder)builder).constraint(Constraint.all(Constraint.range(range.min(), range.max())));
            }
         }
      }
   }

   private static final class MatchesProcessor implements ConfigFieldAnnotationProcessor<Matches> {
      private MatchesProcessor() {
      }

      public void process(Matches matches, MetadataContainerBuilder<?> builder) {
         if (builder instanceof TrackedValue.Builder) {
            Object defaultValue = ((TrackedValue.Builder)builder).getDefaultValue();
            if (defaultValue instanceof String) {
               ((TrackedValue.Builder)builder).constraint(Constraint.matching(matches.value()));
            } else if (defaultValue instanceof CompoundConfigValue && ((CompoundConfigValue)defaultValue).getType().equals(String.class)) {
               ((TrackedValue.Builder)builder).constraint(Constraint.all(Constraint.matching(matches.value())));
            }
         }
      }
   }

   private static final class SerialNameProcessor implements ConfigFieldAnnotationProcessor<SerializedName> {
      private SerialNameProcessor() {
      }

      public void process(SerializedName name, MetadataContainerBuilder<?> builder) {
         builder.metadata(SerializedName.TYPE, nameBuilder -> nameBuilder.withName(name.value()));
      }
   }

   private static final class SerializedNameConventionProcessor implements ConfigFieldAnnotationProcessor<SerializedNameConvention> {
      private final NamingSchemeHelper namingSchemeHelper = new NamingSchemeHelper();

      private SerializedNameConventionProcessor() {
      }

      public void process(SerializedNameConvention annotation, MetadataContainerBuilder<?> builder) {
         builder.metadata(
            SerializedNameConvention.TYPE,
            nameConventionBuilder -> nameConventionBuilder.set(this.namingSchemeHelper.getNamingScheme(annotation, ConfigFieldException::new))
         );
      }
   }
}
