import com.android.repository.api.*;
import java.nio.file.*;
import java.lang.reflect.Method;

public class ScanDiag {
  public static void main(String[] args) throws Exception {
    ProgressIndicator ind = new ConsoleProgressIndicator();
    Object handler = com.android.sdklib.repository.AndroidSdkHandler.getInstance(
        com.android.prefs.AndroidLocationsSingleton.INSTANCE, Paths.get("/opt/android-sdk"));
    Object rm = handler.getClass().getMethod("getSdkManager", ProgressIndicator.class).invoke(handler, (Object) ind);
    rm.getClass().getMethod("setLocalPath", java.nio.file.Path.class).invoke(rm, Paths.get("/opt/android-sdk"));
    rm.getClass().getMethod("loadSynchronously", long.class, ProgressIndicator.class, Downloader.class, SettingsController.class)
      .invoke(rm, 0L, ind, null, null);
    Object pkgs = rm.getClass().getMethod("getPackages").invoke(rm);
    Object local = pkgs.getClass().getMethod("getLocalPackages").invoke(pkgs);
    System.out.println("LOCAL_COUNT=" + ((java.util.Map) local).size());
    ((java.util.Map) local).keySet().forEach(k -> System.out.println("PKG: " + k));
  }
}
