package link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Config;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.ReflectiveConfig;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.Processor;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions.ConfigCreationException;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions.ConfigFieldException;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.ConfigFieldAnnotationProcessors;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.TrackedValueImpl;

public class ReflectiveConfigCreator<C> implements Config.Creator {
   private final Class<C> creatorClass;
   private C instance;

   public ReflectiveConfigCreator(Class<C> creatorClass) {
      this.creatorClass = creatorClass;
   }

   private void createField(Config.SectionBuilder builder, Object object, Field field) throws IllegalAccessException {
      if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) {
         if (!Modifier.isFinal(field.getModifiers())) {
            throw new ConfigFieldException("Field '" + field.getType().getName() + ':' + field.getName() + "' is not final!");
         }

         if (!Modifier.isPublic(field.getModifiers())) {
            field.setAccessible(true);
         }

         Object defaultValue = field.get(object);
         if (defaultValue instanceof TrackedValueImpl) {
            TrackedValueImpl<?> value = (TrackedValueImpl<?>)defaultValue;
            TrackedValueBuilderImpl<?> delegateBuilder = new TrackedValueBuilderImpl<>(value.getDefaultValue(), field.getName());

            for (Annotation annotation : field.getAnnotations()) {
               ConfigFieldAnnotationProcessors.applyAnnotationProcessors(annotation, delegateBuilder);
            }

            if (field.isAnnotationPresent(Processor.class)) {
               Processor processor = field.getAnnotation(Processor.class);

               try {
                  Method method = field.getDeclaringClass().getMethod(processor.value(), TrackedValue.Builder.class);
                  method.invoke(object, delegateBuilder);
               } catch (NoSuchMethodException var11) {
                  throw new ConfigCreationException(
                     "Processor method '" + processor.value() + "' not found for config field '" + this.creatorClass.getName() + "#" + field.getName() + "'."
                  );
               } catch (IllegalAccessException | InvocationTargetException var12) {
                  throw new ConfigCreationException("Exception invoking processor method '" + processor.value() + "': " + var12.getLocalizedMessage());
               }
            }

            TrackedValueImpl delegate = (TrackedValueImpl)delegateBuilder.build();
            if (value.key() != null) {
               throw new IllegalStateException("Unexpected key set in TrackedValue. Please report this at https://github.com/QuiltMC/quilt-config/issues!");
            }

            value.setKey(delegate.key());
            if (!value.metadata.isEmpty()) {
               throw new IllegalStateException(
                  "Unexpected metadata value set in TrackedValue. Please report this at https://github.com/QuiltMC/quilt-config/issues!"
               );
            }

            value.metadata = delegate.metadata;
            if (!value.constraints.isEmpty()) {
               throw new IllegalStateException(
                  "Unexpected constraints value set in TrackedValue. Please report this at https://github.com/QuiltMC/quilt-config/issues!"
               );
            }

            value.constraints = delegate.constraints;
            if (!value.callbacks.isEmpty()) {
               throw new IllegalStateException(
                  "Unexpected callback value set in TrackedValue. Please report this at https://github.com/QuiltMC/quilt-config/issues!"
               );
            }

            value.callbacks = delegate.callbacks;
            builder.field(value);
         } else {
            if (!(defaultValue instanceof ReflectiveConfig.Section)) {
               if (defaultValue == null) {
                  throw new ConfigFieldException("Default value for field '" + field.getName() + "' cannot be null");
               }

               throw new ConfigFieldException(
                  "Class '"
                     + defaultValue.getClass().getName()
                     + "' of field '"
                     + field.getName()
                     + "' of config class '"
                     + field.getDeclaringClass().getName()
                     + "'is not a valid config value: it must be a TrackedValue or implement org.quiltmc.loader.api.Config.Section"
               );
            }

            builder.section(
               field.getName(),
               b -> {
                  for (Annotation annotationx : field.getAnnotations()) {
                     ConfigFieldAnnotationProcessors.applyAnnotationProcessors(annotationx, b);
                  }

                  if (field.isAnnotationPresent(Processor.class)) {
                     Processor processor = field.getAnnotation(Processor.class);

                     try {
                        Method method = field.getDeclaringClass().getMethod(processor.value(), Config.SectionBuilder.class);
                        method.invoke(object, b);
                     } catch (NoSuchMethodException var11x) {
                        throw new ConfigCreationException(
                           "Processor method '"
                              + processor.value()
                              + "' not found for config section '"
                              + this.creatorClass.getName()
                              + "#"
                              + field.getName()
                              + "'."
                        );
                     } catch (IllegalAccessException | InvocationTargetException var12x) {
                        throw new ConfigCreationException("Exception invoking processor method '" + processor.value() + "': " + var12x.getLocalizedMessage());
                     }
                  }

                  for (Field f : defaultValue.getClass().getDeclaredFields()) {
                     if (!f.isSynthetic()) {
                        try {
                           this.createField(b, defaultValue, f);
                        } catch (IllegalAccessException var10x) {
                           throw new RuntimeException(var10x);
                        }
                     }
                  }
               }
            );
         }
      }
   }

   @Override
   public void create(Config.Builder builder) {
      if (this.instance != null) {
         throw new ConfigCreationException("Reflective config creator used more than once!");
      } else {
         try {
            this.instance = this.creatorClass.getDeclaredConstructor().newInstance();

            for (Annotation annotation : this.creatorClass.getAnnotations()) {
               ConfigFieldAnnotationProcessors.applyAnnotationProcessors(annotation, builder);
            }

            for (Field field : this.creatorClass.getDeclaredFields()) {
               this.createField(builder, this.instance, field);
            }

            if (this.creatorClass.isAnnotationPresent(Processor.class)) {
               Processor processor = this.creatorClass.getAnnotation(Processor.class);

               try {
                  Method method = this.creatorClass.getMethod(processor.value(), Config.Builder.class);
                  method.invoke(this.instance, builder);
               } catch (NoSuchMethodException var6) {
                  throw new ConfigCreationException(
                     "Processor method '" + processor.value() + "' not found for config class '" + this.creatorClass.getName() + "'."
                  );
               } catch (IllegalAccessException | InvocationTargetException var7) {
                  throw new ConfigCreationException("Exception invoking processor method '" + processor.value() + "': " + var7.getLocalizedMessage());
               }
            }
         } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException | InstantiationException var8) {
            throw new ConfigCreationException(var8);
         }
      }
   }

   public static <C> ReflectiveConfigCreator<C> of(Class<C> creatorClass) {
      return new ReflectiveConfigCreator<>(creatorClass);
   }

   public C getInstance() {
      if (this.instance == null) {
         throw new RuntimeException("Config not built yet!");
      } else {
         return this.instance;
      }
   }
}
