package link.e4steam.internal.kaleido.lib.quiltconfig.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface Serializer {
   String getFileExtension();

   void serialize(Config var1, OutputStream var2) throws IOException;

   void deserialize(Config var1, InputStream var2) throws IOException;
}
