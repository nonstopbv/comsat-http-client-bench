import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

public class FiberOkHttpEnv implements Env<Request, AutoCloseableOkHttpClientRequestExecutor> {
  @Override
  public AutoCloseableOkHttpClientRequestExecutor newRequestExecutor(int ioParallelism, int maxConnections, int timeout) throws Exception {
    return new AutoCloseableOkHttpClientRequestExecutor((Response r) -> {
      if (!r.isSuccessful())
        throw new AssertionError("Request didn't complete successfully");
    }, ioParallelism, maxConnections, timeout);
  }

  @Override
  public Request newRequest(String uri) throws Exception {
    return new Request.Builder().get().url(uri).build();
  }
}
