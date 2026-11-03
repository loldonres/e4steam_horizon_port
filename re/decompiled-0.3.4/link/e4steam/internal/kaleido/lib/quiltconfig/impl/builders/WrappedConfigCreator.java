package link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Config;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.Processor;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions.ConfigCreationException;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions.ConfigFieldException;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.ConfigFieldAnnotationProcessors;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.util.ConfigUtils;

@Deprecated
public class WrappedConfigCreator<C> implements Config.Creator {
   private final Class<C> creatorClass;
   private C instance;

   public WrappedConfigCreator(Class<C> creatorClass) {
      this.creatorClass = creatorClass;
   }

   private void createField(Config.SectionBuilder builder, Object object, Field field) throws IllegalAccessException {
      if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) {
         if (Modifier.isFinal(field.getModifiers())) {
            System.out
               .println(
                  "(Quilt Config) Field '"
                     + field.getType().getName()
                     + ':'
                     + field.getName()
                     + "' is final! This may cause broken behaviour, and is not recommended. Either move to the new ReflectiveConfig API or remove the final modifier."
               );
         }

         if (!Modifier.isPublic(field.getModifiers())) {
            field.setAccessible(true);
         }

         Object defaultValue = field.get(object);
         if (ConfigUtils.isValidValue(defaultValue)) {
            TrackedValue<?> value = TrackedValue.create(defaultValue, field.getName(), valueBuilder -> {
               field.setAccessible(true);
               valueBuilder.callback(tracked -> {
                  try {
                     field.set(object, tracked.value());
                  } catch (IllegalAccessException var4x) {
                     throw new RuntimeException(var4x);
                  }
               });

               for (Annotation annotation : field.getAnnotations()) {
                  ConfigFieldAnnotationProcessors.applyAnnotationProcessors(annotation, valueBuilder);
               }

               if (field.isAnnotationPresent(Processor.class)) {
                  Processor processor = field.getAnnotation(Processor.class);

                  try {
                     Method method = field.getDeclaringClass().getMethod(processor.value(), TrackedValue.Builder.class);
                     method.invoke(object, valueBuilder);
                  } catch (NoSuchMethodException var7) {
                     throw new ConfigCreationException("Processor method '" + processor.value() + "' not found.");
                  } catch (IllegalAccessException | InvocationTargetException var8) {
                     throw new ConfigCreationException("Exception invoking processor method '" + processor.value() + "': " + var8.getLocalizedMessage());
                  }
               }
            });
            field.set(object, value.getRealValue());
            builder.field(value);
         } else {
            if (!(defaultValue instanceof Config.Section)) {
               if (defaultValue == null) {
                  throw new ConfigFieldException("Default value for field '" + field.getName() + "' cannot be null");
               }

               throw new ConfigFieldException(
                  "Class '"
                     + defaultValue.getClass().getName()
                     + "' of field '"
                     + field.getName()
                     + "' is not a valid config value; must be a basic type, complex type, or implement org.quiltmc.loader.api.Config.Section"
               );
            }

            builder.section(field.getName(), b -> {
               for (Annotation annotation : field.getAnnotations()) {
                  ConfigFieldAnnotationProcessors.applyAnnotationProcessors(annotation, b);
               }

               for (Field f : defaultValue.getClass().getDeclaredFields()) {
                  if (!f.isSynthetic()) {
                     try {
                        this.createField(b, defaultValue, f);
                     } catch (IllegalAccessException var9) {
                        throw new RuntimeException(var9);
                     }
                  }
               }
            });
         }
      }
   }

   @Override
   public void create(Config.Builder builder) {
      if (this.instance != null) {
         throw new ConfigCreationException("Reflective config creator used more than once");
      } else {
         try {
            this.instance = this.creatorClass.newInstance();

            for (Field field : this.creatorClass.getDeclaredFields()) {
               this.createField(builder, this.instance, field);
            }

            if (this.creatorClass.isAnnotationPresent(Processor.class)) {
               Processor processor = this.creatorClass.getAnnotation(Processor.class);

               try {
                  Method method = this.creatorClass.getMethod(processor.value(), Config.Builder.class);
                  method.invoke(this.instance, builder);
               } catch (NoSuchMethodException var6) {
                  throw new ConfigCreationException("Processor method '" + processor.value() + "' not found.");
               } catch (IllegalAccessException | InvocationTargetException var7) {
                  throw new ConfigCreationException("Exception invoking processor method '" + processor.value() + "': " + var7.getLocalizedMessage());
               }
            }
         } catch (IllegalAccessException | InstantiationException var8) {
            throw new ConfigCreationException(var8);
         }
      }
   }

   public static <C> WrappedConfigCreator<C> of(Class<C> creatorClass) {
      return new WrappedConfigCreator<>(creatorClass);
   }

   public C getInstance() {
      if (this.instance == null) {
         throw new RuntimeException("Config not built yet");
      } else {
         return this.instance;
      }
   }
}
