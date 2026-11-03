package link.e4steam.internal.kaleido.lib.quiltconfig.api.serializers;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map.Entry;
import link.e4steam.internal.kaleido.lib.nightconfig.core.CommentedConfig;
import link.e4steam.internal.kaleido.lib.nightconfig.core.InMemoryCommentedFormat;
import link.e4steam.internal.kaleido.lib.nightconfig.core.UnmodifiableCommentedConfig;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ConfigParser;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ConfigWriter;
import link.e4steam.internal.kaleido.lib.nightconfig.toml.TomlParser;
import link.e4steam.internal.kaleido.lib.nightconfig.toml.TomlWriter;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Config;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Constraint;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.MarshallingUtils;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Serializer;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.Comment;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ConfigSerializableObject;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueKey;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueList;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueMap;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueTreeNode;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.util.SerializerUtils;

public final class TomlSerializer implements Serializer {
   public static final TomlSerializer INSTANCE = new TomlSerializer();
   private final ConfigParser<CommentedConfig> parser = new TomlParser();
   private final ConfigWriter writer = new TomlWriter();

   private TomlSerializer() {
   }

   @Override
   public String getFileExtension() {
      return "toml";
   }

   @Override
   public void serialize(Config config, OutputStream to) {
      this.writer.write(write(config, createCommentedConfig(), config.nodes()), to);
   }

   @Override
   public void deserialize(Config config, InputStream from) {
      CommentedConfig read = this.parser.parse(from);

      for (TrackedValue<?> trackedValue : config.values()) {
         for (ValueKey key : SerializerUtils.getPossibleKeys(config, trackedValue)) {
            String stringKey = key.toString();
            if (read.contains(stringKey)) {
               ((TrackedValue<Object>)trackedValue).setValue(
                  MarshallingUtils.coerce(
                     read.get(stringKey),
                     trackedValue.getDefaultValue(),
                     (c, entryConsumer) -> c.entrySet().forEach(e -> entryConsumer.put(e.getKey(), e.getValue()))
                  ),
                  false
               );
            }
         }
      }
   }

   private static List<Object> convertList(List<?> list) {
      List<Object> result = new ArrayList<>(list.size());

      for (Object value : list) {
         result.add(convertAny(value));
      }

      return result;
   }

   private static UnmodifiableCommentedConfig convertMap(ValueMap<?> map) {
      CommentedConfig result = createCommentedConfig();

      for (Entry<String, ?> entry : map.entrySet()) {
         List<String> key = new ArrayList<>();
         key.add(entry.getKey());
         result.add(key, convertAny(entry.getValue()));
      }

      return result;
   }

   private static Object convertAny(Object value) {
      if (value instanceof ValueMap) {
         return convertMap((ValueMap<?>)value);
      } else if (value instanceof ValueList) {
         return convertList((ValueList)value);
      } else {
         return value instanceof ConfigSerializableObject ? convertAny(((ConfigSerializableObject)value).getRepresentation()) : value;
      }
   }

   private static CommentedConfig write(Config config, CommentedConfig commentedConfig, Iterable<ValueTreeNode> nodes) {
      for (ValueTreeNode node : nodes) {
         List<String> comments = new ArrayList<>();
         if (node.hasMetadata(Comment.TYPE)) {
            for (String string : node.metadata(Comment.TYPE)) {
               comments.add(string);
            }
         }

         ValueKey key = SerializerUtils.getSerializedKey(config, node);
         if (!(node instanceof TrackedValue)) {
            write(config, commentedConfig, (ValueTreeNode.Section)node);
         } else {
            TrackedValue<?> value = (TrackedValue<?>)node;
            Object defaultValue = value.getDefaultValue();
            SerializerUtils.createEnumOptionsComment(defaultValue).ifPresent(comments::add);

            for (Constraint<?> constraint : value.constraints()) {
               comments.add(constraint.getRepresentation());
            }

            Optional<String> defaultValueComment = SerializerUtils.getDefaultValueString(defaultValue);
            defaultValueComment.ifPresent(s -> comments.add("default: " + s));
            commentedConfig.add(toNightConfigSerializable(key), convertAny(value.getRealValue()));
         }

         if (!comments.isEmpty()) {
            commentedConfig.setComment(toNightConfigSerializable(key), " " + String.join("\n ", comments));
         }
      }

      return commentedConfig;
   }

   private static CommentedConfig createCommentedConfig() {
      return InMemoryCommentedFormat.defaultInstance().createConfig(LinkedHashMap::new);
   }

   private static List<String> toNightConfigSerializable(ValueKey key) {
      List<String> listKey = new ArrayList<>();
      key.forEach(listKey::add);
      return listKey;
   }
}
