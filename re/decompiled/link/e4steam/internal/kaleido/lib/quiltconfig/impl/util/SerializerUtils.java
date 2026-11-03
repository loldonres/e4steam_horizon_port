package link.e4steam.internal.kaleido.lib.quiltconfig.impl.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Config;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.Alias;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.SerializedName;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.SerializedNameConvention;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.CompoundConfigValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueKey;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueTreeNode;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.values.ValueKeyImpl;

public class SerializerUtils {
   public static Optional<String> getDefaultValueString(Object defaultValue) {
      try {
         if (defaultValue.getClass().getMethod("toString").getDeclaringClass() != Object.class && !(defaultValue instanceof CompoundConfigValue)) {
            return Optional.of(defaultValue.toString());
         }
      } catch (NoSuchMethodException var2) {
      }

      return Optional.empty();
   }

   public static Optional<String> createEnumOptionsComment(Object defaultValue) {
      if (defaultValue.getClass().isEnum()) {
         StringBuilder options = new StringBuilder("options: ");
         Object[] enumConstants = defaultValue.getClass().getEnumConstants();
         int i = 0;

         for (int enumConstantsLength = enumConstants.length; i < enumConstantsLength; i++) {
            Object o = enumConstants[i];
            options.append(o);
            if (i < enumConstantsLength - 1) {
               options.append(", ");
            }
         }

         return Optional.of(options.toString());
      } else {
         return Optional.empty();
      }
   }

   public static List<ValueKey> getPossibleKeys(Config config, ValueTreeNode value) {
      List<ValueKey> possibleKeys = new ArrayList<>();
      possibleKeys.add(getSerializedKey(config, value));
      if (value.hasMetadata(Alias.TYPE)) {
         for (String alias : value.metadata(Alias.TYPE)) {
            List<String> aliasKey = new ArrayList<>();

            for (int i = 0; i < value.key().length(); i++) {
               if (i != value.key().length() - 1) {
                  aliasKey.add(value.key().getKeyComponent(i));
               } else {
                  aliasKey.add(alias);
               }
            }

            possibleKeys.add(new ValueKeyImpl(aliasKey.toArray(new String[0])));
         }
      }

      return possibleKeys;
   }

   public static ValueKey getSerializedKey(Config config, ValueTreeNode value) {
      List<String> serializedKey = new ArrayList<>();
      ValueKey key = value.key();
      List<String> rawKey = new ArrayList<>();

      for (int i = 0; i < value.key().length(); i++) {
         rawKey.add(key.getKeyComponent(i));
         ValueTreeNode currentNode = config.getNode(rawKey);
         serializedKey.add(getSerializedName(currentNode));
      }

      return new ValueKeyImpl(serializedKey.toArray(new String[0]));
   }

   public static String getSerializedName(ValueTreeNode value) {
      if (value.hasMetadata(SerializedName.TYPE)) {
         return value.metadata(SerializedName.TYPE).getName();
      } else {
         return value.hasMetadata(SerializedNameConvention.TYPE)
            ? value.metadata(SerializedNameConvention.TYPE).coerce(value.key().getLastComponent())
            : value.key().getLastComponent();
      }
   }
}
