package link.e4steam.internal.kaleido.lib.nightconfig.core.utils;

import java.io.IOException;
import java.io.Writer;

public interface WriterSupplier {
   Writer get() throws IOException;
}
