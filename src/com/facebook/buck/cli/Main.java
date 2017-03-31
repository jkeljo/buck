/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.android.DefaultAndroidDirectoryResolver;
import com.facebook.buck.android.NoAndroidSdkException;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactCacheBuckConfig;
import com.facebook.buck.artifact_cache.ArtifactCaches;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.Configs;
import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.CounterRegistryImpl;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.BuckInitializationDurationEvent;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.DaemonEvent;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.event.listener.AbstractConsoleEventBusListener;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.event.listener.CacheRateStatsListener;
import com.facebook.buck.event.listener.ChromeTraceBuildListener;
import com.facebook.buck.event.listener.FileSerializationEventBusListener;
import com.facebook.buck.event.listener.JavaUtilsLoggingBuildListener;
import com.facebook.buck.event.listener.LoadBalancerEventsListener;
import com.facebook.buck.event.listener.LoggingBuildListener;
import com.facebook.buck.event.listener.MachineReadableLoggerListener;
import com.facebook.buck.event.listener.ProgressEstimator;
import com.facebook.buck.event.listener.PublicAnnouncementManager;
import com.facebook.buck.event.listener.RuleKeyLoggerListener;
import com.facebook.buck.event.listener.SimpleConsoleEventBusListener;
import com.facebook.buck.event.listener.SuperConsoleConfig;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.AsynchronousDirectoryContentsCleaner;
import com.facebook.buck.io.BuckPaths;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.PathOrGlobMatcher;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.ProjectWatch;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.WatchmanCursor;
import com.facebook.buck.io.WatchmanDiagnosticEventListener;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.ConsoleHandlerState;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.LogConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.CellProvider;
import com.facebook.buck.rules.CoercedTypeCache;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.rules.RelativeCellName;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.shell.WorkerProcessPool;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.test.TestConfig;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.timing.NanosAdjustedClock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.AsyncCloseable;
import com.facebook.buck.util.BgProcessKiller;
import com.facebook.buck.util.BuckArgsMethods;
import com.facebook.buck.util.BuckIsDyingException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.InterruptionFailedException;
import com.facebook.buck.util.Libc;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.PkillProcessManager;
import com.facebook.buck.util.PrintStreamProcessExecutorFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.WatchmanWatcher;
import com.facebook.buck.util.WatchmanWatcherException;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.facebook.buck.util.cache.WatchedFileHashCache;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.CommandMode;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.network.RemoteLogBuckConfig;
import com.facebook.buck.util.perf.PerfStatsTracking;
import com.facebook.buck.util.perf.ProcessTracker;
import com.facebook.buck.util.shutdown.NonReentrantSystemExit;
import com.facebook.buck.util.versioncontrol.DefaultVersionControlCmdLineInterfaceFactory;
import com.facebook.buck.util.versioncontrol.VersionControlBuckConfig;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.reflect.ClassPath;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.martiansoftware.nailgun.NGContext;
import com.martiansoftware.nailgun.NGListeningAddress;
import com.martiansoftware.nailgun.NGServer;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import org.kohsuke.args4j.CmdLineException;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;


public final class Main {

  /**
   * Trying again won't help.
   */
  public static final int FAIL_EXIT_CODE = 1;

  /**
   * Trying again later might work.
   */
  public static final int BUSY_EXIT_CODE = 2;

  /**
   * The command was interrupted
   */
  public static final int INTERRUPTED_EXIT_CODE = 130;

  private static final Optional<String> BUCKD_LAUNCH_TIME_NANOS =
      Optional.ofNullable(System.getProperty("buck.buckd_launch_time_nanos"));
  private static final String BUCK_BUILD_ID_ENV_VAR = "BUCK_BUILD_ID";

  private static final String BUCKD_COLOR_DEFAULT_ENV_VAR = "BUCKD_COLOR_DEFAULT";

  private static final Duration DAEMON_SLAYER_TIMEOUT = Duration.ofDays(1);

  private static final Duration SUPER_CONSOLE_REFRESH_RATE = Duration.ofMillis(67);

  private static final Duration HANG_DETECTOR_TIMEOUT = Duration.ofMinutes(5);

  /**
   * Path to a directory of static content that should be served by the {@link WebServer}.
   */
  private static final String STATIC_CONTENT_DIRECTORY = System.getProperty(
      "buck.path_to_static_content", "webserver/static");
  private static final int DISK_IO_STATS_TIMEOUT_SECONDS = 10;
  private static final int EXECUTOR_SERVICES_TIMEOUT_SECONDS = 60;
  private static final int COUNTER_AGGREGATOR_SERVICE_TIMEOUT_SECONDS = 20;

  private final InputStream stdIn;
  private final PrintStream stdOut;
  private final PrintStream stdErr;

  private final Architecture architecture;

  private static final Semaphore commandSemaphore = new Semaphore(1);
  private static volatile Optional<NGContext> commandSemaphoreNgClient = Optional.empty();

  // Ensure we only have one instance of this, so multiple trash cleaning
  // operations are serialized on one queue.
  private static final AsynchronousDirectoryContentsCleaner TRASH_CLEANER =
      new AsynchronousDirectoryContentsCleaner();

  private final Platform platform;

  // It's important to re-use this object for perf:
  // http://wiki.fasterxml.com/JacksonBestPracticesPerformance
  private final ObjectMapper objectMapper;

  // Ignore changes to generated Xcode project files and editors' backup files
  // so we don't dump buckd caches on every command.
  private static final ImmutableSet<PathOrGlobMatcher> DEFAULT_IGNORE_GLOBS =
      ImmutableSet.of(
          new PathOrGlobMatcher("**/*.pbxproj"),
          new PathOrGlobMatcher("**/*.xcscheme"),
          new PathOrGlobMatcher("**/*.xcworkspacedata"),
          // Various editors' temporary files
          new PathOrGlobMatcher("**/*~"),
          // Emacs
          new PathOrGlobMatcher("**/#*#"),
          new PathOrGlobMatcher("**/.#*"),
          // Vim
          new PathOrGlobMatcher("**/*.swo"),
          new PathOrGlobMatcher("**/*.swp"),
          new PathOrGlobMatcher("**/*.swpx"),
          new PathOrGlobMatcher("**/*.un~"),
          new PathOrGlobMatcher("**/.netrhwist"),
          // Eclipse
          new PathOrGlobMatcher(".idea"),
          new PathOrGlobMatcher(".iml"),
          new PathOrGlobMatcher("**/*.pydevproject"),
          new PathOrGlobMatcher(".project"),
          new PathOrGlobMatcher(".metadata"),
          new PathOrGlobMatcher("**/*.tmp"),
          new PathOrGlobMatcher("**/*.bak"),
          new PathOrGlobMatcher("**/*~.nib"),
          new PathOrGlobMatcher(".classpath"),
          new PathOrGlobMatcher(".settings"),
          new PathOrGlobMatcher(".loadpath"),
          new PathOrGlobMatcher(".externalToolBuilders"),
          new PathOrGlobMatcher(".cproject"),
          new PathOrGlobMatcher(".buildpath"),
          // Mac OS temp files
          new PathOrGlobMatcher(".DS_Store"),
          new PathOrGlobMatcher(".AppleDouble"),
          new PathOrGlobMatcher(".LSOverride"),
          new PathOrGlobMatcher(".Spotlight-V100"),
          new PathOrGlobMatcher(".Trashes"),
          // Windows
          new PathOrGlobMatcher("$RECYCLE.BIN"),
          // Sublime
          new PathOrGlobMatcher(".*.sublime-workspace"));

  private static final Logger LOG = Logger.get(Main.class);

  private static boolean isSessionLeader;

  @Nullable
  private static FileLock resourcesFileLock = null;

  private static final HangMonitor.AutoStartInstance HANG_MONITOR =
      new HangMonitor.AutoStartInstance(
          (input) -> {
            LOG.info(
                "No recent activity, dumping thread stacks (`tr , '\\n'` to decode): %s",
                input);
          },
          HANG_DETECTOR_TIMEOUT);

  private static final NonReentrantSystemExit NON_REENTRANT_SYSTEM_EXIT =
      new NonReentrantSystemExit();

  private static void addTransitiveCells(
      Set<Cell> cellsBuilder,
      Cell cell) {
    if (cellsBuilder.add(cell)) {
      cell.getKnownRoots().stream()
          .map(cell::getCell)
          .forEach(c -> addTransitiveCells(cellsBuilder, c));
    }
  }

  // Collect all transitive cells.
  private static ImmutableCollection<Cell> getTransitiveCells(Cell root) {
    HashSet<Cell> cellsBuilder = new HashSet<>();
    addTransitiveCells(cellsBuilder, root);
    return ImmutableList.copyOf(cellsBuilder);
  }

  /**
   * Daemon used to monitor the file system and cache build rules between Main() method
   * invocations is static so that it can outlive Main() objects and survive for the lifetime
   * of the potentially long running Buck process.
   */
  private static final class Daemon implements Closeable {

    private final Cell cell;
    private final CoercedTypeCache coercedTypeCache;
    private final Parser parser;
    private final ImmutableList<ProjectFileHashCache> hashCaches;
    private final EventBus fileEventBus;
    private final Optional<WebServer> webServer;
    private final ConcurrentMap<String, WorkerProcessPool> persistentWorkerPools;
    private final VersionedTargetGraphCache versionedTargetGraphCache;
    private final ActionGraphCache actionGraphCache;
    private final BroadcastEventListener broadcastEventListener;
    private final RuleKeyCacheRecycler<RuleKey> defaultRuleKeyFactoryCacheRecycler;

    private ImmutableMap<Path, WatchmanCursor> cursor;

    public Daemon(
        Cell cell,
        ObjectMapper objectMapper,
        Optional<WebServer> webServerToReuse) {
      this.cell = cell;
      this.fileEventBus = new EventBus("file-change-events");

      // Collect all transitive cells.
      ImmutableCollection<Cell> cells = getTransitiveCells(cell);

      // Setup the stacked file hash cache from all cells.
      ImmutableList.Builder<ProjectFileHashCache> hashCachesBuilder = ImmutableList.builder();
      cells.forEach(
          (Cell subCell) -> {
            WatchedFileHashCache watchedCache = new WatchedFileHashCache(subCell.getFilesystem());
            fileEventBus.register(watchedCache);
            hashCachesBuilder.add(watchedCache);
          });
      hashCachesBuilder.add(
          DefaultFileHashCache.createBuckOutFileHashCache(
              cell.getFilesystem().replaceBlacklistedPaths(ImmutableSet.of()),
              cell.getFilesystem().getBuckPaths().getBuckOut()));
      this.hashCaches = hashCachesBuilder.build();


      this.broadcastEventListener = new BroadcastEventListener();
      this.actionGraphCache = new ActionGraphCache(broadcastEventListener);
      this.versionedTargetGraphCache = new VersionedTargetGraphCache();

      TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory(objectMapper);
      this.coercedTypeCache = new CoercedTypeCache(typeCoercerFactory);
      this.parser = new Parser(
          broadcastEventListener,
          cell.getBuckConfig().getView(ParserConfig.class),
          typeCoercerFactory,
          coercedTypeCache,
          new ConstructorArgMarshaller(coercedTypeCache));
      fileEventBus.register(parser);
      fileEventBus.register(actionGraphCache);

      // Build the the rule key cache recycler.
      this.defaultRuleKeyFactoryCacheRecycler =
          RuleKeyCacheRecycler.createAndRegister(
              fileEventBus,
              new DefaultRuleKeyCache<>(),
              RichStream.from(cells)
                  .map(Cell::getFilesystem)
                  .toImmutableSet());

      if (webServerToReuse.isPresent()) {
        webServer = webServerToReuse;
      } else {
        webServer = createWebServer(cell.getBuckConfig(), cell.getFilesystem(), objectMapper);
      }
      if (!initWebServer()) {
        LOG.warn("Can't start web server");
      }
      ImmutableMap.Builder<Path, WatchmanCursor> cursorBuilder = ImmutableMap.builder();
      if (cell.getBuckConfig().getView(ParserConfig.class).getWatchmanCursor() ==
          WatchmanWatcher.CursorType.CLOCK_ID &&
          !cell.getWatchman().getClockIds().isEmpty()) {
        for (Map.Entry<Path, String> entry : cell.getWatchman().getClockIds().entrySet()) {
          cursorBuilder.put(entry.getKey(), new WatchmanCursor(entry.getValue()));
        }
      } else {
        LOG.debug("Falling back to named cursors: %s", cell.getWatchman().getProjectWatches());
        for (Path cellPath : cell.getWatchman().getProjectWatches().keySet()) {
          cursorBuilder.put(
              cellPath,
              new WatchmanCursor(
                  new StringBuilder("n:buckd").append(UUID.randomUUID()).toString()));
        }
      }
      cursor = cursorBuilder.build();
      LOG.debug("Using Watchman Cursor: %s", cursor);
      persistentWorkerPools = new ConcurrentHashMap<>();
      JavaUtilsLoggingBuildListener.ensureLogFileIsWritten(cell.getFilesystem());
    }

    private Optional<WebServer> createWebServer(
        BuckConfig config,
        ProjectFilesystem filesystem,
        ObjectMapper objectMapper) {
      Optional<Integer> port = getValidWebServerPort(config);
      if (port.isPresent()) {
        WebServer webServer = new WebServer(
            port.get(),
            filesystem,
            STATIC_CONTENT_DIRECTORY,
            objectMapper);
        return Optional.of(webServer);
      } else {
        return Optional.empty();
      }
    }

    /**
     * If the return value is not absent, then the port is a nonnegative integer. This means that
     * specifying a port of -1 effectively disables the WebServer.
     */
    public static Optional<Integer> getValidWebServerPort(BuckConfig config) {
      // Enable the web httpserver if it is given by command line parameter or specified in
      // .buckconfig. The presence of a nonnegative port number is sufficient.
      Optional<String> serverPort =
          Optional.ofNullable(System.getProperty("buck.httpserver.port"));
      if (!serverPort.isPresent()) {
        serverPort = config.getValue("httpserver", "port");
      }

      if (!serverPort.isPresent() || serverPort.get().isEmpty()) {
        return Optional.empty();
      }

      String rawPort = serverPort.get();
      int port;
      try {
        port = Integer.parseInt(rawPort, 10);
        LOG.debug("Starting up web server on port %d.", port);
      } catch (NumberFormatException e) {
        LOG.error("Could not parse port for httpserver: %s.", rawPort);
        return Optional.empty();
      }

      return port >= 0 ? Optional.of(port) : Optional.empty();
    }

    public Optional<WebServer> getWebServer() {
      return webServer;
    }

    private Parser getParser() {
      return parser;
    }

    private VersionedTargetGraphCache getVersionedTargetGraphCache() {
      return versionedTargetGraphCache;
    }

    private ActionGraphCache getActionGraphCache() {
      return actionGraphCache;
    }

    private BroadcastEventListener getBroadcastEventListener() {
      return broadcastEventListener;
    }

    private ImmutableList<ProjectFileHashCache> getFileHashCaches() {
      return hashCaches;
    }

    private ConcurrentMap<String, WorkerProcessPool> getPersistentWorkerPools() {
      return persistentWorkerPools;
    }

    public RuleKeyCacheRecycler<RuleKey> getDefaultRuleKeyFactoryCacheRecycler() {
      return defaultRuleKeyFactoryCacheRecycler;
    }

    private void watchClient(final NGContext context) {
      context.addClientListener(() -> {
        if (isSessionLeader && commandSemaphoreNgClient.orElse(null) == context) {
          LOG.info("killing background processes on client disconnection");
          // Process no longer wants work done on its behalf.
          BgProcessKiller.killBgProcesses();
        }

        // Synchronize on parser object so that the main command processing thread is not
        // interrupted mid way through a Parser cache update by the Thread.interrupt() call
        // triggered by System.exit(). The Parser cache will be reused by subsequent commands
        // so needs to be left in a consistent state even if the current command is interrupted
        // due to a client disconnection.
        synchronized (parser) {
          LOG.info("Client disconnected.");
          // Client should no longer be connected, but printing helps detect false disconnections.
          context.err.println("Client disconnected.");

          throw new InterruptedException("Client disconnected.");
        }
      });
    }

    private void watchFileSystem(
        CommandEvent commandEvent,
        BuckEventBus eventBus,
        WatchmanWatcher watchmanWatcher,
        WatchmanWatcher.FreshInstanceAction watchmanFreshInstanceAction)
        throws IOException, InterruptedException {

      // Synchronize on parser object so that all outstanding watch events are processed
      // as a single, atomic Parser cache update and are not interleaved with Parser cache
      // invalidations triggered by requests to parse build files or interrupted by client
      // disconnections.
      synchronized (parser) {
        parser.recordParseStartTime(eventBus);
        fileEventBus.post(commandEvent);
        watchmanWatcher.postEvents(
            eventBus,
            watchmanFreshInstanceAction);
      }
    }

    /**
     * @return true if the web server was started successfully.
     */
    private boolean initWebServer() {
      if (webServer.isPresent()) {
        Optional<ArtifactCache> servedCache = ArtifactCaches.newServedCache(
            new ArtifactCacheBuckConfig(cell.getBuckConfig()),
            cell.getFilesystem());
        try {
          webServer.get().updateAndStartIfNeeded(servedCache);
          return true;
        } catch (WebServer.WebServerException e) {
          LOG.error(e);
        }
      }
      return false;
    }

    public EventBus getFileEventBus() {
      return fileEventBus;
    }

    public ImmutableMap<Path, WatchmanCursor> getWatchmanCursor() {
      return cursor;
    }

    @Override
    public void close() throws IOException {
      shutdownPersistentWorkerPools();
      shutdownWebServer();
    }

    private void shutdownPersistentWorkerPools() {
      for (WorkerProcessPool pool : persistentWorkerPools.values()) {
        try {
          pool.close();
        } catch (Exception e) {
          LOG.error(e);
        }
      }
    }

    private void shutdownWebServer() {
      if (webServer.isPresent()) {
        try {
          webServer.get().stop();
        } catch (WebServer.WebServerException e) {
          LOG.error(e);
        }
      }
    }
  }

  @Nullable
  private static volatile Daemon daemon;

  /**
   * Get or create Daemon.
   */
  @VisibleForTesting
  static Daemon getDaemon(
      Cell cell,
      ObjectMapper objectMapper)
      throws IOException {
    Path rootPath = cell.getFilesystem().getRootPath();
    Optional<WebServer> webServer = Optional.empty();
    if (daemon == null) {
      LOG.debug("Starting up daemon for project root [%s]", rootPath);
      daemon = new Daemon(cell, objectMapper, webServer);
    } else {
      // Buck daemons cache build files within a single project root, changing to a different
      // project root is not supported and will likely result in incorrect builds. The buck and
      // buckd scripts attempt to enforce this, so a change in project root is an error that
      // should be reported rather than silently worked around by invalidating the cache and
      // creating a new daemon object.
      Path parserRoot = cell.getFilesystem().getRootPath();
      if (!rootPath.equals(parserRoot)) {
        throw new HumanReadableException(String.format("Unsupported root path change from %s to %s",
            rootPath, parserRoot));
      }

      // If Buck config or the AndroidDirectoryResolver has changed, invalidate the cache and
      // create a new daemon.
      if (!daemon.cell.equals(cell)) {
        LOG.warn(
            "Shutting down and restarting daemon on config or directory resolver change (%s != %s)",
            daemon.cell,
            cell);
        if (shouldReuseWebServer(cell)) {
          webServer = daemon.getWebServer();
          LOG.info("Reusing web server");
        } else {
          daemon.close();
        }
        daemon = new Daemon(cell, objectMapper, webServer);
      }
    }
    return daemon;
  }

  private WatchmanWatcher createWatchmanWatcher(
      Daemon daemon,
      ImmutableMap<Path, ProjectWatch> projectWatch,
      EventBus fileChangeEventBus,
      ImmutableSet<PathOrGlobMatcher> ignorePaths,
      Watchman watchman) {
    return new WatchmanWatcher(
        projectWatch,
        fileChangeEventBus,
        ignorePaths,
        watchman,
        daemon.getWatchmanCursor());
  }

  private static BroadcastEventListener getBroadcastEventListener(
      boolean isDaemon,
      Cell rootCell,
      ObjectMapper objectMapper)
      throws IOException {
    if (isDaemon) {
      return getDaemon(rootCell, objectMapper).getBroadcastEventListener();
    }
    return new BroadcastEventListener();
  }

  private static boolean shouldReuseWebServer(Cell newCell) {
    if (newCell == null || daemon == null || daemon.cell == null) {
      return false;
    }
    Optional<Integer> portFromOldConfig =
        Daemon.getValidWebServerPort(daemon.cell.getBuckConfig());
    Optional<Integer> portFromUpdatedConfig =
        Daemon.getValidWebServerPort(newCell.getBuckConfig());

    return portFromOldConfig.equals(portFromUpdatedConfig);
  }

  @VisibleForTesting
  @SuppressWarnings("PMD.EmptyCatchBlock")
  static void resetDaemon() {
    if (daemon != null) {
      try {
        LOG.info("Closing daemon on reset request.");
        daemon.close();
      } catch (IOException e) {
        // Swallow exceptions while closing daemon.
      }
    }
    daemon = null;
  }

  @VisibleForTesting
  public Main(
      PrintStream stdOut,
      PrintStream stdErr,
      InputStream stdIn) {
    this.stdOut = stdOut;
    this.stdErr = stdErr;
    this.stdIn = stdIn;
    this.architecture = Architecture.detect();
    this.platform = Platform.detect();
    this.objectMapper = ObjectMappers.newDefaultInstance();
  }

  /* Define all error handling surrounding main command */
  private void runMainThenExit(
      String[] args,
      Optional<NGContext> context,
      final long initTimestamp) {
    installUncaughtExceptionHandler(context);

    Path projectRoot = Paths.get(".");
    int exitCode = FAIL_EXIT_CODE;
    BuildId buildId = getBuildId(context);

    // Only post an overflow event if Watchman indicates a fresh instance event
    // after our initial query.
    WatchmanWatcher.FreshInstanceAction watchmanFreshInstanceAction =
        daemon == null
            ? WatchmanWatcher.FreshInstanceAction.NONE
            : WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT;

    // Get the client environment, either from this process or from the Nailgun context.
    ImmutableMap<String, String> clientEnvironment = getClientEnvironment(context);

    try {
      CommandMode commandMode = CommandMode.RELEASE;
      exitCode = runMainWithExitCode(
          buildId,
          projectRoot,
          context,
          clientEnvironment,
          commandMode,
          watchmanFreshInstanceAction,
          initTimestamp,
          args);
    } catch (IOException e) {
      if (e.getMessage().startsWith("No space left on device")) {
        (new Console(
            Verbosity.STANDARD_INFORMATION,
            stdOut,
            stdErr,
            new Ansi(
                AnsiEnvironmentChecking.environmentSupportsAnsiEscapes(
                    platform, clientEnvironment)))).printBuildFailure(e.getMessage());
      } else {
        LOG.error(e);
      }
    } catch (HumanReadableException e) {
      Console console = new Console(
          Verbosity.STANDARD_INFORMATION,
          stdOut,
          stdErr,
          new Ansi(
              AnsiEnvironmentChecking.environmentSupportsAnsiEscapes(platform, clientEnvironment)));
      console.printBuildFailure(e.getHumanReadableErrorMessage());
    } catch (InterruptionFailedException e) { // Command could not be interrupted.
      if (context.isPresent()) {
        context.get().getNGServer().shutdown(true); // Exit process to halt command execution.
      }
    } catch (BuckIsDyingException e) {
      LOG.warn(e, "Fallout because buck was already dying");
    } catch (Throwable t) {
      LOG.error(t, "Uncaught exception at top level");
    } finally {
      LOG.debug("Done.");
      LogConfig.flushLogs();
      // Exit explicitly so that non-daemon threads (of which we use many) don't
      // keep the VM alive.
      System.exit(exitCode);
    }
  }

  /**
   * @param buildId an identifier for this command execution.
   * @param context an optional NGContext that is present if running inside a Nailgun server.
   * @param initTimestamp Value of System.nanoTime() when process got main()/nailMain() invoked.
   * @param unexpandedCommandLineArgs command line arguments
   * @return an exit code or {@code null} if this is a process that should not exit
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  public int runMainWithExitCode(
      BuildId buildId,
      Path projectRoot,
      Optional<NGContext> context,
      ImmutableMap<String, String> clientEnvironment,
      CommandMode commandMode,
      WatchmanWatcher.FreshInstanceAction watchmanFreshInstanceAction,
      final long initTimestamp,
      String... unexpandedCommandLineArgs)
      throws IOException, InterruptedException {

    String[] args = BuckArgsMethods.expandAtFiles(unexpandedCommandLineArgs, projectRoot);

    // Parse the command line args.
    BuckCommand command = new BuckCommand();
    AdditionalOptionsCmdLineParser cmdLineParser = new AdditionalOptionsCmdLineParser(command);
    try {
      cmdLineParser.parseArgument(args);
    } catch (CmdLineException e) {
      // Can't go through the console for prettification since that needs the BuckConfig, and that
      // needs to be created with the overrides, which are parsed from the command line here, which
      // required the console to print the message that parsing has failed. So just write to stderr
      // and be done with it.
      stdErr.println(e.getLocalizedMessage());
      stdErr.println("For help see 'buck --help'.");
      return 1;
    }

    {
      // Return help strings fast if the command is a help request.
      OptionalInt result = command.runHelp(stdErr);
      if (result.isPresent()) {
        return result.getAsInt();
      }
    }

    // Setup logging.
    if (commandMode.isLoggingEnabled()) {

      // Reset logging each time we run a command while daemonized.
      // This will cause us to write a new log per command.
      LOG.debug("Rotating log.");
      LogConfig.flushLogs();
      LogConfig.setupLogging(command.getLogConfig());

      if (LOG.isDebugEnabled()) {
        Long gitCommitTimestamp = Long.getLong("buck.git_commit_timestamp");
        String buildDateStr;
        if (gitCommitTimestamp == null) {
          buildDateStr = "(unknown)";
        } else {
          buildDateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(
              new Date(TimeUnit.SECONDS.toMillis(gitCommitTimestamp)));
        }
        String buildRev = System.getProperty("buck.git_commit", "(unknown)");
        LOG.debug(
            "Starting up (build date %s, rev %s), args: %s",
            buildDateStr,
            buildRev,
            Arrays.toString(args));
        LOG.debug("System properties: %s", System.getProperties());
      }
    }

    // Setup filesystem and buck config.
    Path canonicalRootPath = projectRoot.toRealPath().normalize();
    Config config = Configs.createDefaultConfig(
        canonicalRootPath,
        command.getConfigOverrides().getForCell(RelativeCellName.ROOT_CELL_NAME));
    ProjectFilesystem filesystem = new ProjectFilesystem(canonicalRootPath, config);
    DefaultCellPathResolver cellPathResolver =
        new DefaultCellPathResolver(filesystem.getRootPath(), config);
    BuckConfig buckConfig = new BuckConfig(
        config,
        filesystem,
        architecture,
        platform,
        clientEnvironment,
        cellPathResolver);
    ImmutableSet<Path> projectWatchList = ImmutableSet.<Path>builder()
        .add(canonicalRootPath)
        .addAll(
            buckConfig.getView(ParserConfig.class).getWatchCells() ?
                cellPathResolver.getTransitivePathMapping().values() :
                ImmutableList.of())
        .build();
    Optional<ImmutableList<String>> allowedJavaSpecificiationVersions =
        buckConfig.getAllowedJavaSpecificationVersions();
    if (allowedJavaSpecificiationVersions.isPresent()) {
      String specificationVersion = System.getProperty("java.specification.version");
      boolean javaSpecificationVersionIsAllowed = allowedJavaSpecificiationVersions
          .get()
          .contains(specificationVersion);
      if (!javaSpecificationVersionIsAllowed) {
        throw new HumanReadableException(
            "Current Java version '%s' is not in the allowed java specification versions:\n%s",
            specificationVersion,
            Joiner.on(", ").join(allowedJavaSpecificiationVersions.get()));
      }
    }

    // Setup the console.
    Verbosity verbosity = VerbosityParser.parse(args);
    Optional<String> color;
    if (context.isPresent() && (context.get().getEnv() != null)) {
      String colorString = context.get().getEnv().getProperty(BUCKD_COLOR_DEFAULT_ENV_VAR);
      color = Optional.ofNullable(colorString);
    } else {
      color = Optional.empty();
    }
    final Console console = new Console(
        verbosity,
        stdOut,
        stdErr,
        buckConfig.createAnsi(color));

    // No more early outs: if this command is not read only, acquire the command semaphore to
    // become the only executing read/write command.
    // This must happen immediately before the try block to ensure that the semaphore is released.
    boolean commandSemaphoreAcquired = false;
    boolean shouldCleanUpTrash = false;
    if (!command.isReadOnly()) {
      commandSemaphoreAcquired = commandSemaphore.tryAcquire();
      if (!commandSemaphoreAcquired) {
        LOG.warn("Buck server was busy executing a command. Maybe retrying later will help.");
        return BUSY_EXIT_CODE;
      }
    }

    try {
      if (commandSemaphoreAcquired) {
        commandSemaphoreNgClient = context;
      }

      if (!command.isReadOnly()) {
        Optional<String> currentVersion =
            filesystem.readFileIfItExists(filesystem.getBuckPaths().getCurrentVersionFile());
        BuckPaths unconfiguredPaths =
            filesystem.getBuckPaths().withConfiguredBuckOut(filesystem.getBuckPaths().getBuckOut());
        if (!currentVersion.isPresent() ||
            !currentVersion.get().equals(BuckVersion.getVersion()) ||
            (filesystem.exists(unconfiguredPaths.getGenDir(), LinkOption.NOFOLLOW_LINKS) &&
                (filesystem.isSymLink(unconfiguredPaths.getGenDir()) ^
                    buckConfig.getBuckOutCompatLink()))) {
          // Migrate any version-dependent directories (which might be huge) to a trash directory
          // so we can delete it asynchronously after the command is done.
          moveToTrash(
              filesystem,
              console,
              buildId,
              filesystem.getBuckPaths().getAnnotationDir(),
              filesystem.getBuckPaths().getGenDir(),
              filesystem.getBuckPaths().getScratchDir(),
              filesystem.getBuckPaths().getResDir());
          shouldCleanUpTrash = true;
          filesystem.mkdirs(filesystem.getBuckPaths().getCurrentVersionFile().getParent());
          filesystem.writeContentsToPath(
              BuckVersion.getVersion(),
              filesystem.getBuckPaths().getCurrentVersionFile());
        }
      }

      AndroidBuckConfig androidBuckConfig = new AndroidBuckConfig(buckConfig, platform);
      AndroidDirectoryResolver androidDirectoryResolver =
          new DefaultAndroidDirectoryResolver(
              filesystem.getRootPath().getFileSystem(),
              clientEnvironment,
              androidBuckConfig.getBuildToolsVersion(),
              androidBuckConfig.getNdkVersion());

      ProcessExecutor processExecutor = new DefaultProcessExecutor(console);

      Clock clock;
      boolean enableThreadCpuTime = buckConfig.getBooleanValue(
          "build",
          "enable_thread_cpu_time",
          true);
      if (BUCKD_LAUNCH_TIME_NANOS.isPresent()) {
        long nanosEpoch = Long.parseLong(BUCKD_LAUNCH_TIME_NANOS.get(), 10);
        LOG.verbose("Using nanos epoch: %d", nanosEpoch);
        clock = new NanosAdjustedClock(nanosEpoch, enableThreadCpuTime);
      } else {
        clock = new DefaultClock(enableThreadCpuTime);
      }

      ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
      try (Watchman watchman =
               buildWatchman(
                   context,
                   parserConfig,
                   projectWatchList,
                   clientEnvironment,
                   console,
                   clock)) {
        final boolean isDaemon = context.isPresent() && (watchman != Watchman.NULL_WATCHMAN);

        if (!isDaemon && shouldCleanUpTrash) {
          // Clean up the trash on a background thread if this was a
          // non-buckd read-write command. (We don't bother waiting
          // for it to complete; the thread is a daemon thread which
          // will just be terminated at shutdown time.)
          TRASH_CLEANER.startCleaningDirectory(filesystem.getBuckPaths().getTrashDir());
        }

        KnownBuildRuleTypesFactory factory = new KnownBuildRuleTypesFactory(
            processExecutor,
            androidDirectoryResolver);

        Cell rootCell = CellProvider.createForLocalBuild(
            filesystem,
            watchman,
            buckConfig,
            command.getConfigOverrides(),
            factory)
            .getCellByPath(filesystem.getRootPath());

        int exitCode;
        ImmutableList<BuckEventListener> eventListeners = ImmutableList.of();
        ExecutionEnvironment executionEnvironment = new DefaultExecutionEnvironment(
            clientEnvironment,
            System.getProperties());

        ImmutableList.Builder<ProjectFileHashCache> allCaches = ImmutableList.builder();

        // Build up the hash cache, which is a collection of the stateful cell cache and some
        // per-run caches.
        //
        // TODO(coneko, ruibm, agallagher): Determine whether we can use the existing filesystem
        // object that is in scope instead of creating a new rootCellProjectFilesystem. The primary
        // difference appears to be that filesystem is created with a Config that is used to produce
        // ImmutableSet<PathOrGlobMatcher> and BuckPaths for the ProjectFilesystem, whereas this one
        // uses the defaults.
        ProjectFilesystem rootCellProjectFilesystem =
            ProjectFilesystem.createNewOrThrowHumanReadableException(
                rootCell.getFilesystem().getRootPath());
        if (isDaemon) {
          allCaches.addAll(getFileHashCachesFromDaemon(rootCell));
        } else {
          getTransitiveCells(rootCell).stream()
              .map(cell -> DefaultFileHashCache.createDefaultFileHashCache(cell.getFilesystem()))
              .forEach(allCaches::add);
          allCaches.add(
              DefaultFileHashCache.createBuckOutFileHashCache(
                  rootCellProjectFilesystem,
                  rootCell.getFilesystem().getBuckPaths().getBuckOut()));
        }

        // A cache which caches hashes of cell-relative paths which may have been ignore by
        // the main cell cache, and only serves to prevent rehashing the same file multiple
        // times in a single run.
        allCaches.add(DefaultFileHashCache.createDefaultFileHashCache(rootCellProjectFilesystem));
        allCaches.addAll(DefaultFileHashCache.createOsRootDirectoriesCaches());

        StackedFileHashCache fileHashCache = new StackedFileHashCache(allCaches.build());

        Optional<WebServer> webServer = getWebServerIfDaemon(context, rootCell);
        Optional<ConcurrentMap<String, WorkerProcessPool>> persistentWorkerPools =
            getPersistentWorkerPoolsIfDaemon(context, rootCell);

        TestConfig testConfig = new TestConfig(buckConfig);
        ArtifactCacheBuckConfig cacheBuckConfig = new ArtifactCacheBuckConfig(buckConfig);

        ExecutorService diskIoExecutorService = MostExecutors.newSingleThreadExecutor("Disk I/O");
        ListeningExecutorService httpWriteExecutorService =
            getHttpWriteExecutorService(cacheBuckConfig);
        ScheduledExecutorService counterAggregatorExecutor =
            Executors.newSingleThreadScheduledExecutor(new CommandThreadFactory(
                "CounterAggregatorThread"));
        VersionControlStatsGenerator vcStatsGenerator;

        // Eventually, we'll want to get allow websocket and/or nailgun clients to specify locale
        // when connecting. For now, we'll use the default from the server environment.
        Locale locale = Locale.getDefault();

        // Create a cached thread pool for cpu intensive tasks
        Map<ExecutorPool, ListeningExecutorService> executors = new HashMap<>();
        executors.put(ExecutorPool.CPU, listeningDecorator(
            Executors.newCachedThreadPool()));
        // Create a thread pool for network I/O tasks
        executors.put(ExecutorPool.NETWORK, newDirectExecutorService());
        executors.put(
            ExecutorPool.PROJECT,
            listeningDecorator(
                MostExecutors.newMultiThreadExecutor(
                    "Project",
                    buckConfig.getNumThreads())));

        // Create and register the event buses that should listen to broadcast events.
        // If the build doesn't have a daemon create a new instance.
        BroadcastEventListener broadcastEventListener =
            getBroadcastEventListener(isDaemon, rootCell, objectMapper);

        boolean useTheForce = command.subcommand instanceof AbstractCommand &&
            ((AbstractCommand) command.subcommand).shouldUseTheForce();

        // The order of resources in the try-with-resources block is important: the BuckEventBus
        // must be the last resource, so that it is closed first and can deliver its queued events
        // to the other resources before they are closed.
        InvocationInfo invocationInfo = InvocationInfo.of(
            buildId,
            isSuperConsoleEnabled(console),
            isDaemon,
            command.getSubCommandNameForLogging(),
            filesystem.getBuckPaths().getLogDir());
        try (
            GlobalStateManager.LoggerIsMappedToThreadScope loggerThreadMappingScope =
                GlobalStateManager.singleton().setupLoggers(
                    invocationInfo,
                    console.getStdErr(),
                    stdErr,
                    verbosity);
            AbstractConsoleEventBusListener consoleListener =
                createConsoleEventListener(
                    clock,
                    new SuperConsoleConfig(buckConfig, useTheForce),
                    console,
                    testConfig.getResultSummaryVerbosity(),
                    executionEnvironment,
                    webServer,
                    locale,
                    filesystem.getBuckPaths().getLogDir().resolve("test.log"));
            AsyncCloseable asyncCloseable = new AsyncCloseable(diskIoExecutorService);
            DefaultBuckEventBus buildEventBus = new DefaultBuckEventBus(clock, buildId);
            BroadcastEventListener.BroadcastEventBusClosable broadcastEventBusClosable =
                broadcastEventListener.addEventBus(buildEventBus);

            // This makes calls to LOG.error(...) post to the EventBus, instead of writing to
            // stderr.
            Closeable logErrorToEventBus = loggerThreadMappingScope.setWriter(
                createWriterForConsole(consoleListener));

            // NOTE: This will only run during the lifetime of the process and will flush on close.
            CounterRegistry counterRegistry = new CounterRegistryImpl(
                counterAggregatorExecutor,
                buildEventBus,
                buckConfig.getCountersFirstFlushIntervalMillis(),
                buckConfig.getCountersFlushIntervalMillis());
            PerfStatsTracking perfStatsTracking = new PerfStatsTracking(
                buildEventBus,
                invocationInfo);
            ProcessTracker processTracker =
                buckConfig.isProcessTrackerEnabled() && platform != Platform.WINDOWS ?
                    new ProcessTracker(
                        buildEventBus,
                        invocationInfo,
                        isDaemon,
                        buckConfig.isProcessTrackerDeepEnabled()) : null;
        ) {

          LOG.debug(invocationInfo.toLogLine(args));

          buildEventBus.register(HANG_MONITOR.getHangMonitor());

          ArtifactCaches artifactCacheFactory = new ArtifactCaches(
              cacheBuckConfig,
              buildEventBus,
              filesystem,
              executionEnvironment.getWifiSsid(),
              httpWriteExecutorService,
              Optional.of(asyncCloseable)
          );

          ProgressEstimator progressEstimator =
              new ProgressEstimator(
                  filesystem.resolve(filesystem.getBuckPaths().getBuckOut())
                      .resolve(ProgressEstimator.PROGRESS_ESTIMATIONS_JSON),
                  buildEventBus,
                  objectMapper);
          consoleListener.setProgressEstimator(progressEstimator);

          BuildEnvironmentDescription buildEnvironmentDescription =
              getBuildEnvironmentDescription(
                  executionEnvironment,
                  buckConfig);

          Iterable<BuckEventListener> commandEventListeners =
              command.getSubcommand().isPresent() ?
                  command
                      .getSubcommand()
                      .get()
                      .getEventListeners(invocationInfo.getLogDirectoryPath(), filesystem) :
                  ImmutableList.of();

          eventListeners = addEventListeners(
              buildEventBus,
              rootCell.getFilesystem(),
              invocationInfo,
              rootCell.getBuckConfig(),
              webServer,
              clock,
              consoleListener,
              counterRegistry,
              commandEventListeners
          );

          if (commandMode == CommandMode.RELEASE && buckConfig.isPublicAnnouncementsEnabled()) {
            PublicAnnouncementManager announcementManager = new PublicAnnouncementManager(
                clock,
                buildEventBus,
                consoleListener,
                buckConfig.getRepository().orElse("unknown"),
                new RemoteLogBuckConfig(buckConfig),
                executors.get(ExecutorPool.CPU));
            announcementManager.getAndPostAnnouncements();
          }

          // This needs to be after the registration of the event listener so they can pick it up.
          if (watchmanFreshInstanceAction == WatchmanWatcher.FreshInstanceAction.NONE) {
            buildEventBus.post(DaemonEvent.newDaemonInstance());
          }


          if (command.subcommand instanceof AbstractCommand) {
            AbstractCommand subcommand = (AbstractCommand) command.subcommand;
            VersionControlBuckConfig vcBuckConfig = new VersionControlBuckConfig(buckConfig);
            if (!commandMode.equals(CommandMode.TEST) && (
                subcommand.isSourceControlStatsGatheringEnabled() ||
                vcBuckConfig.shouldGenerateStatistics())) {
              vcStatsGenerator = new VersionControlStatsGenerator(
                  diskIoExecutorService,
                  new DefaultVersionControlCmdLineInterfaceFactory(
                      rootCell.getFilesystem().getRootPath(),
                      new PrintStreamProcessExecutorFactory(),
                      vcBuckConfig,
                      buckConfig.getEnvironment()),
                  buildEventBus);

              vcStatsGenerator.generateStatsAsync();
            }
          }

          ImmutableList<String> remainingArgs = args.length > 1
              ? ImmutableList.copyOf(Arrays.copyOfRange(args, 1, args.length))
              : ImmutableList.of();

          CommandEvent.Started startedEvent = CommandEvent.started(
              args.length > 0 ? args[0] : "",
              remainingArgs,
              isDaemon,
              getBuckPID());
          buildEventBus.post(startedEvent);

          // Create or get Parser and invalidate cached command parameters.
          CoercedTypeCache coercedTypeCache = null;
          Parser parser = null;
          VersionedTargetGraphCache versionedTargetGraphCache = null;
          ActionGraphCache actionGraphCache = null;
          Optional<RuleKeyCacheRecycler<RuleKey>> defaultRuleKeyFactoryCacheRecycler =
              Optional.empty();

          if (isDaemon) {
            try {
              Daemon daemon = getDaemon(rootCell, objectMapper);
              WatchmanWatcher watchmanWatcher = createWatchmanWatcher(
                  daemon,
                  watchman.getProjectWatches(),
                  daemon.getFileEventBus(),
                  ImmutableSet.<PathOrGlobMatcher>builder()
                      .addAll(filesystem.getIgnorePaths())
                      .addAll(DEFAULT_IGNORE_GLOBS)
                      .build(),
                  watchman);
              coercedTypeCache = daemon.coercedTypeCache;
              parser = getParserFromDaemon(
                  context,
                  rootCell,
                  startedEvent,
                  buildEventBus,
                  watchmanWatcher,
                  watchmanFreshInstanceAction);
              versionedTargetGraphCache = daemon.getVersionedTargetGraphCache();
              actionGraphCache = daemon.getActionGraphCache();
              if (buckConfig.getRuleKeyCaching()) {
                LOG.debug("Using rule key calculation caching");
                defaultRuleKeyFactoryCacheRecycler =
                    Optional.of(daemon.getDefaultRuleKeyFactoryCacheRecycler());
              }
            } catch (WatchmanWatcherException | IOException e) {
              buildEventBus.post(
                  ConsoleEvent.warning(
                      "Watchman threw an exception while parsing file changes.\n%s",
                      e.getMessage()));
            }
          }

          if (versionedTargetGraphCache == null) {
            versionedTargetGraphCache = new VersionedTargetGraphCache();
          }

          if (actionGraphCache == null) {
            actionGraphCache = new ActionGraphCache(broadcastEventListener);
          }

          if (coercedTypeCache == null || parser == null) {
            TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory(objectMapper);
            coercedTypeCache = new CoercedTypeCache(typeCoercerFactory);
            parser = new Parser(
                broadcastEventListener,
                rootCell.getBuckConfig().getView(ParserConfig.class),
                typeCoercerFactory,
                coercedTypeCache,
                new ConstructorArgMarshaller(coercedTypeCache));
          }

          // Because the Parser is potentially constructed before the CounterRegistry,
          // we need to manually register its counters after it's created.
          //
          // The counters will be unregistered once the counter registry is closed.
          counterRegistry.registerCounters(parser.getCounters());

          JavaUtilsLoggingBuildListener.ensureLogFileIsWritten(rootCell.getFilesystem());

          Optional<ProcessManager> processManager;
          if (platform == Platform.WINDOWS) {
            processManager = Optional.empty();
          } else {
            processManager = Optional.of(new PkillProcessManager(processExecutor));
          }
          Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier =
              createAndroidPlatformTargetSupplier(
                  androidDirectoryResolver,
                  androidBuckConfig,
                  buildEventBus);

          // At this point, we have parsed options but haven't started
          // running the command yet.  This is a good opportunity to
          // augment the event bus with our serialize-to-file
          // event-listener.
          if (command.subcommand instanceof AbstractCommand) {
            AbstractCommand subcommand = (AbstractCommand) command.subcommand;
            Optional<Path> eventsOutputPath = subcommand.getEventsOutputPath();
            if (eventsOutputPath.isPresent()) {
              BuckEventListener listener =
                  new FileSerializationEventBusListener(eventsOutputPath.get(), objectMapper);
              buildEventBus.register(listener);
            }
          }

          buildEventBus.post(
              new BuckInitializationDurationEvent(
                  TimeUnit.NANOSECONDS.toMillis(
                      System.nanoTime() - initTimestamp)));

          try {
            exitCode = command.run(
                CommandRunnerParams.builder()
                    .setConsole(console)
                    .setStdIn(stdIn)
                    .setCell(rootCell)
                    .setAndroidPlatformTargetSupplier(androidPlatformTargetSupplier)
                    .setArtifactCacheFactory(artifactCacheFactory)
                    .setBuckEventBus(buildEventBus)
                    .setCoercedTypeCache(coercedTypeCache)
                    .setParser(parser)
                    .setPlatform(platform)
                    .setEnvironment(clientEnvironment)
                    .setJavaPackageFinder(
                        rootCell.getBuckConfig().getView(JavaBuckConfig.class)
                            .createDefaultJavaPackageFinder())
                    .setObjectMapper(objectMapper)
                    .setClock(clock)
                    .setProcessManager(processManager)
                    .setPersistentWorkerPools(persistentWorkerPools)
                    .setWebServer(webServer)
                    .setBuckConfig(buckConfig)
                    .setFileHashCache(fileHashCache)
                    .setExecutors(executors)
                    .setBuildEnvironmentDescription(buildEnvironmentDescription)
                    .setVersionedTargetGraphCache(versionedTargetGraphCache)
                    .setActionGraphCache(actionGraphCache)
                    .setKnownBuildRuleTypesFactory(factory)
                    .setInvocationInfo(Optional.of(invocationInfo))
                    .setDefaultRuleKeyFactoryCacheRecycler(defaultRuleKeyFactoryCacheRecycler)
                    .build());
          } catch (InterruptedException | ClosedByInterruptException e) {
            exitCode = INTERRUPTED_EXIT_CODE;
            buildEventBus.post(CommandEvent.interrupted(startedEvent, INTERRUPTED_EXIT_CODE));
            throw e;
          }
          // We've reserved exitCode 2 for timeouts, and some commands (e.g. run) may violate this
          // Let's avoid an infinite loop
          if (exitCode == BUSY_EXIT_CODE) {
            exitCode = FAIL_EXIT_CODE; // Some loss of info here, but better than looping
            LOG.error("Buck return with exit code %d which we use to indicate busy status. " +
                "This is probably propagating an exit code from a sub process or tool. " +
                "Coercing to %d to avoid retries.", BUSY_EXIT_CODE, FAIL_EXIT_CODE);
          }
          // Wait for HTTP writes to complete.
          closeHttpExecutorService(
              cacheBuckConfig, Optional.of(buildEventBus), httpWriteExecutorService);
          closeExecutorService(
              "CounterAggregatorExecutor",
              counterAggregatorExecutor,
              COUNTER_AGGREGATOR_SERVICE_TIMEOUT_SECONDS);
          buildEventBus.post(CommandEvent.finished(startedEvent, exitCode));
        } catch (Throwable t) {
          LOG.debug(t, "Failing build on exception.");
          closeHttpExecutorService(
              cacheBuckConfig, Optional.empty(), httpWriteExecutorService);
          closeDiskIoExecutorService(diskIoExecutorService);
          flushEventListeners(console, buildId, eventListeners);
          throw t;
        } finally {
          if (commandSemaphoreAcquired) {
            commandSemaphoreNgClient = Optional.empty();
            BgProcessKiller.disarm();
            commandSemaphore.release(); // Allow another command to execute while outputting traces.
            commandSemaphoreAcquired = false;
          }
          if (isDaemon && shouldCleanUpTrash) {
            // Clean up the trash in the background if this was a buckd
            // read-write command. (We don't bother waiting for it to
            // complete; the cleaner will ensure subsequent cleans are
            // serialized with this one.)
            TRASH_CLEANER.startCleaningDirectory(filesystem.getBuckPaths().getTrashDir());
          }
          // shut down the cached thread pools
          for (ExecutorPool p : executors.keySet()) {
            closeExecutorService(p.toString(), executors.get(p), EXECUTOR_SERVICES_TIMEOUT_SECONDS);
          }
        }
        if (context.isPresent() && !rootCell.getBuckConfig().getFlushEventsBeforeExit()) {
          context.get().in.close(); // Avoid client exit triggering client disconnection handling.
          context.get().exit(exitCode); // Allow nailgun client to exit while outputting traces.
        }

        closeDiskIoExecutorService(diskIoExecutorService);
        flushEventListeners(console, buildId, eventListeners);
        return exitCode;
      }
    } finally {
      if (commandSemaphoreAcquired) {
        commandSemaphoreNgClient = Optional.empty();
        BgProcessKiller.disarm();
        commandSemaphore.release();
      }
    }
  }

  private void flushEventListeners(
      Console console,
      BuildId buildId,
      ImmutableList<BuckEventListener> eventListeners) throws InterruptedException {
    for (BuckEventListener eventListener : eventListeners) {
      try {
        eventListener.outputTrace(buildId);
      } catch (RuntimeException e) {
        PrintStream stdErr = console.getStdErr();
        stdErr.println("Ignoring non-fatal error!  The stack trace is below:");
        e.printStackTrace(stdErr);
      }
    }
  }

  private static void moveToTrash(
      ProjectFilesystem filesystem,
      Console console,
      BuildId buildId,
      Path... pathsToMove) throws IOException {
    Path trashPath = filesystem.getBuckPaths().getTrashDir().resolve(buildId.toString());
    filesystem.mkdirs(trashPath);
    for (Path pathToMove : pathsToMove) {
      try {
        // Technically this might throw AtomicMoveNotSupportedException,
        // but we're moving a path within buck-out, so we don't expect this
        // to throw.
        //
        // If it does throw, we'll complain loudly and synchronously delete
        // the file instead.
        filesystem.move(
            pathToMove,
            trashPath.resolve(pathToMove.getFileName()),
            StandardCopyOption.ATOMIC_MOVE);
      } catch (NoSuchFileException e) {
        LOG.verbose(e, "Ignoring missing path %s", pathToMove);
      } catch (AtomicMoveNotSupportedException e) {
        console.getStdErr().format(
            "Atomic moves not supported, falling back to synchronous delete: %s",
            e);
        MoreFiles.deleteRecursivelyIfExists(pathToMove);
      }
    }
  }

  private static final Watchman buildWatchman(
      Optional<NGContext> context,
      ParserConfig parserConfig,
      ImmutableSet<Path> projectWatchList,
      ImmutableMap<String, String> clientEnvironment,
      Console console,
      Clock clock) throws InterruptedException {
    Watchman watchman;
    if (context.isPresent() || parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN) {
      watchman = Watchman.build(
          projectWatchList,
          clientEnvironment,
          console,
          clock,
          parserConfig.getWatchmanQueryTimeoutMs());

      LOG.debug(
          "Watchman capabilities: %s Project watches: %s Glob handler config: %s " +
              "Query timeout ms config: %s",
          watchman.getCapabilities(),
          watchman.getProjectWatches(),
          parserConfig.getGlobHandler(),
          parserConfig.getWatchmanQueryTimeoutMs());

    } else {
      watchman = Watchman.NULL_WATCHMAN;
      LOG.debug("Not using Watchman, context present: %s, glob handler: %s",
          context.isPresent(),
          parserConfig.getGlobHandler());
    }
    return watchman;
  }

  private static void closeExecutorService(
      String executorName,
      ExecutorService executorService,
      long timeoutSeconds) throws InterruptedException {
    executorService.shutdown();
    LOG.info(
        "Awaiting termination of %s executor service. Waiting for all jobs to complete, " +
            "or up to maximum of %s seconds...",
        executorName,
        timeoutSeconds);
    executorService.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
    if (!executorService.isTerminated()) {
      LOG.warn(
          "%s executor service is still running after shutdown request and " +
              "%s second timeout. Shutting down forcefully..",
          executorName,
          timeoutSeconds);
      executorService.shutdownNow();
    }
  }

  private static void closeDiskIoExecutorService(
      ExecutorService diskIoExecutorService) throws InterruptedException {
    closeExecutorService("Disk IO", diskIoExecutorService, DISK_IO_STATS_TIMEOUT_SECONDS);
  }

  private static void closeHttpExecutorService(
      ArtifactCacheBuckConfig buckConfig,
      Optional<BuckEventBus> eventBus,
      ListeningExecutorService httpWriteExecutorService) throws InterruptedException {
    closeExecutorService(
        "HTTP Write",
        httpWriteExecutorService,
        buckConfig.getHttpWriterShutdownTimeout());

    if (eventBus.isPresent()) {
      eventBus.get().post(HttpArtifactCacheEvent.newShutdownEvent());
    }
  }

  private static ListeningExecutorService getHttpWriteExecutorService(
      ArtifactCacheBuckConfig buckConfig) {
    if (buckConfig.hasAtLeastOneWriteableCache()) {
      ExecutorService executorService = MostExecutors.newMultiThreadExecutor(
          "HTTP Write",
          buckConfig.getHttpMaxConcurrentWrites());

      return listeningDecorator(executorService);
    } else {
      return newDirectExecutorService();
    }
  }

  @VisibleForTesting
  static Supplier<AndroidPlatformTarget> createAndroidPlatformTargetSupplier(
      final AndroidDirectoryResolver androidDirectoryResolver,
      final AndroidBuckConfig androidBuckConfig,
      final BuckEventBus eventBus) {
    // TODO(mbolin): Only one such Supplier should be created per Cell per Buck execution.
    // Currently, only one Supplier is created per Buck execution because Main creates the Supplier
    // and passes it from above all the way through, but it is not parameterized by Cell.
    //
    // TODO(mbolin): Every build rule that uses AndroidPlatformTarget must include the result
    // of its getCacheName() method in its RuleKey.
    return new Supplier<AndroidPlatformTarget>() {

      @Nullable
      private AndroidPlatformTarget androidPlatformTarget;

      @Nullable
      private NoAndroidSdkException exception;

      @Override
      public AndroidPlatformTarget get() {
        if (androidPlatformTarget != null) {
          return androidPlatformTarget;
        } else if (exception != null) {
          throw exception;
        }

        String androidPlatformTargetId;
        Optional<String> target = androidBuckConfig.getAndroidTarget();
        if (target.isPresent()) {
          androidPlatformTargetId = target.get();
        } else {
          androidPlatformTargetId = AndroidPlatformTarget.DEFAULT_ANDROID_PLATFORM_TARGET;
          eventBus.post(ConsoleEvent.warning(
              "No Android platform target specified. Using default: %s",
              androidPlatformTargetId));
        }

        Optional<AndroidPlatformTarget> androidPlatformTargetOptional = AndroidPlatformTarget
            .getTargetForId(
                androidPlatformTargetId,
                androidDirectoryResolver,
                androidBuckConfig.getAaptOverride(),
                androidBuckConfig.getAapt2Override());
        if (androidPlatformTargetOptional.isPresent()) {
          androidPlatformTarget = androidPlatformTargetOptional.get();
          return androidPlatformTarget;
        } else {
          exception = NoAndroidSdkException.createExceptionForPlatformThatCannotBeFound(
              androidPlatformTargetId);
          throw exception;
        }
      }
    };
  }


  private static ConsoleHandlerState.Writer createWriterForConsole(
      final AbstractConsoleEventBusListener console) {
    return new ConsoleHandlerState.Writer() {
      @Override
      public void write(String line) throws IOException {
        console.printSevereWarningDirectly(line);
      }

      @Override
      public void flush() throws IOException {
        // Intentional no-op.
      }

      @Override
      public void close() throws IOException {
        // Intentional no-op.
      }
    };
  }

  /**
   * @return the client environment, which is either the process environment or the
   * environment sent to the daemon by the Nailgun client. This method should always be used
   * in preference to System.getenv() and should be the only call to System.getenv() within the
   * Buck codebase to ensure that the use of the Buck daemon is transparent.
   */
  @SuppressWarnings({"unchecked", "rawtypes"}) // Safe as Property is a Map<String, String>.
  private static ImmutableMap<String, String> getClientEnvironment(Optional<NGContext> context) {
    if (context.isPresent()) {
      return ImmutableMap.<String, String>copyOf((Map) context.get().getEnv());
    } else {
      return ImmutableMap.copyOf(System.getenv());
    }
  }

  private Parser getParserFromDaemon(
      Optional<NGContext> context,
      Cell cell,
      CommandEvent commandEvent,
      BuckEventBus eventBus,
      WatchmanWatcher watchmanWatcher,
      WatchmanWatcher.FreshInstanceAction watchmanFreshInstanceAction)
      throws IOException, InterruptedException {
    // Wire up daemon to new client and get cached Parser.
    Daemon daemonForParser = getDaemon(cell, objectMapper);
    daemonForParser.watchClient(context.get());
    daemonForParser.watchFileSystem(
        commandEvent,
        eventBus,
        watchmanWatcher,
        watchmanFreshInstanceAction);
    return daemon.getParser();
  }

  private ImmutableList<ProjectFileHashCache> getFileHashCachesFromDaemon(Cell cell)
      throws IOException {
    Daemon daemon = getDaemon(cell, objectMapper);
    return daemon.getFileHashCaches();
  }

  private Optional<WebServer> getWebServerIfDaemon(
      Optional<NGContext> context,
      Cell cell)
      throws IOException {
    if (context.isPresent()) {
      Daemon daemon = getDaemon(cell, objectMapper);
      return daemon.getWebServer();
    }
    return Optional.empty();
  }

  private Optional<ConcurrentMap<String, WorkerProcessPool>> getPersistentWorkerPoolsIfDaemon(
      Optional<NGContext> context,
      Cell cell)
      throws IOException {
    if (context.isPresent()) {
      Daemon daemon = getDaemon(cell, objectMapper);
      return Optional.of(daemon.getPersistentWorkerPools());
    }
    return Optional.empty();
  }

  private void loadListenersFromBuckConfig(
      ImmutableList.Builder<BuckEventListener> eventListeners,
      ProjectFilesystem projectFilesystem,
      BuckConfig config) {
    final ImmutableSet<String> paths = config.getListenerJars();
    if (paths.isEmpty()) {
      return;
    }

    URL[] urlsArray = new URL[paths.size()];
    try {
      int i = 0;
      for (String path : paths) {
        String urlString = "file://" + projectFilesystem.resolve(Paths.get(path));
        urlsArray[i] = new URL(urlString);
        i++;
      }
    } catch (MalformedURLException e) {
      throw new HumanReadableException(e.getMessage());
    }

    // This ClassLoader is disconnected to allow searching the JARs (and just the JARs) for classes.
    ClassLoader isolatedClassLoader = URLClassLoader.newInstance(urlsArray, null);

    ImmutableSet<ClassPath.ClassInfo> classInfos;
    try {
      ClassPath classPath = ClassPath.from(isolatedClassLoader);
      classInfos = classPath.getTopLevelClasses();
    } catch (IOException e) {
      throw new HumanReadableException(e.getMessage());
    }

    // This ClassLoader will actually work, because it is joined to the parent ClassLoader.
    URLClassLoader workingClassLoader = URLClassLoader.newInstance(urlsArray);

    for (ClassPath.ClassInfo classInfo : classInfos) {
      String className = classInfo.getName();
      try {
        Class<?> aClass = Class.forName(className, true, workingClassLoader);
        if (BuckEventListener.class.isAssignableFrom(aClass)) {
          BuckEventListener listener = aClass.asSubclass(BuckEventListener.class).newInstance();
          eventListeners.add(listener);
        }
      } catch (ReflectiveOperationException e) {
        throw new HumanReadableException(
            "Error loading event listener class '%s': %s: %s",
            className,
            e.getClass(),
            e.getMessage());
      }
    }
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  private ImmutableList<BuckEventListener> addEventListeners(
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      InvocationInfo invocationInfo,
      BuckConfig buckConfig,
      Optional<WebServer> webServer,
      Clock clock,
      AbstractConsoleEventBusListener consoleEventBusListener,
      CounterRegistry counterRegistry,
      Iterable<BuckEventListener> commandSpecificEventListeners
  ) {
    ImmutableList.Builder<BuckEventListener> eventListenersBuilder =
        ImmutableList.<BuckEventListener>builder()
            .add(new JavaUtilsLoggingBuildListener())
            .add(consoleEventBusListener)
            .add(new LoggingBuildListener());

    if (buckConfig.isChromeTraceCreationEnabled()) {
      try {
        eventListenersBuilder.add(new ChromeTraceBuildListener(
            projectFilesystem,
            invocationInfo,
            clock,
            objectMapper,
            buckConfig.getMaxTraces(),
            buckConfig.getCompressTraces()));
      } catch (IOException e) {
        LOG.error("Unable to create ChromeTrace listener!");
      }
    } else {
      LOG.warn("::: ChromeTrace listener disabled");
    }
    if (webServer.isPresent()) {
      eventListenersBuilder.add(webServer.get().createListener());
    }

    loadListenersFromBuckConfig(eventListenersBuilder, projectFilesystem, buckConfig);

    if (buckConfig.isRuleKeyLoggerEnabled()) {
      eventListenersBuilder.add(new RuleKeyLoggerListener(
          projectFilesystem,
          invocationInfo,
          MostExecutors.newSingleThreadExecutor(
              new CommandThreadFactory(getClass().getName()))));
    }

    if (buckConfig.isMachineReadableLoggerEnabled()) {
      try {
        eventListenersBuilder.add(
            new MachineReadableLoggerListener(
                invocationInfo,
                projectFilesystem,
                MostExecutors.newSingleThreadExecutor(
                    new CommandThreadFactory(getClass().getName()))));
      } catch (FileNotFoundException e) {
        LOG.warn("Unable to open stream for machine readable log file.");
      }
    }


    eventListenersBuilder.add(new LoadBalancerEventsListener(counterRegistry));
    eventListenersBuilder.add(new CacheRateStatsListener(buckEventBus));
    eventListenersBuilder.add(new WatchmanDiagnosticEventListener(buckEventBus));

    eventListenersBuilder.addAll(commandSpecificEventListeners);

    ImmutableList<BuckEventListener> eventListeners = eventListenersBuilder.build();
    eventListeners.forEach(buckEventBus::register);


    return eventListeners;
  }


  private BuildEnvironmentDescription getBuildEnvironmentDescription(
      ExecutionEnvironment executionEnvironment,
      BuckConfig buckConfig) {
    ImmutableMap.Builder<String, String> environmentExtraData = ImmutableMap.builder();

    return BuildEnvironmentDescription.of(
        executionEnvironment,
        new ArtifactCacheBuckConfig(buckConfig).getArtifactCacheModesRaw(),
        environmentExtraData.build());
  }

  private AbstractConsoleEventBusListener createConsoleEventListener(
      Clock clock,
      SuperConsoleConfig config,
      Console console,
      TestResultSummaryVerbosity testResultSummaryVerbosity,
      ExecutionEnvironment executionEnvironment,
      Optional<WebServer> webServer,
      Locale locale,
      Path testLogPath) {
    if (isSuperConsoleEnabled(console)) {
      SuperConsoleEventBusListener superConsole = new SuperConsoleEventBusListener(
          config,
          console,
          clock,
          testResultSummaryVerbosity,
          executionEnvironment,
          webServer,
          locale,
          testLogPath,
          TimeZone.getDefault());
      superConsole.startRenderScheduler(
          SUPER_CONSOLE_REFRESH_RATE.toMillis(),
          TimeUnit.MILLISECONDS);
      return superConsole;
    }
    return new SimpleConsoleEventBusListener(
        console,
        clock,
        testResultSummaryVerbosity,
        locale,
        testLogPath,
        executionEnvironment);
  }

  private boolean isSuperConsoleEnabled(Console console) {
    return Platform.WINDOWS != Platform.detect() &&
        console.getAnsi().isAnsiTerminal() &&
        !console.getVerbosity().shouldPrintCommand() &&
        console.getVerbosity().shouldPrintStandardInformation();
  }


  /**
   * A helper method to retrieve the process ID of Buck. The return value from the JVM has to match
   * the following pattern: {PID}@{Hostname}. It it does not match the return value is 0.
   * @return the PID or 0L.
   */
  private static long getBuckPID() {
    String pid = ManagementFactory.getRuntimeMXBean().getName();
    return (pid != null && pid.matches("^\\d+@\\w+$"))
        ? Long.parseLong(pid.split("@")[0])
        : 0L;
  }

  private static BuildId getBuildId(Optional<NGContext> context) {
    String specifiedBuildId;
    if (context.isPresent()) {
      specifiedBuildId = context.get().getEnv().getProperty(BUCK_BUILD_ID_ENV_VAR);
    } else {
      specifiedBuildId = System.getenv().get(BUCK_BUILD_ID_ENV_VAR);
    }
    if (specifiedBuildId == null) {
      throw new RuntimeException("Missing build id value.");
    } else {
      return new BuildId(specifiedBuildId);
    }
  }

  private static void installUncaughtExceptionHandler(final Optional<NGContext> context) {
    // Override the default uncaught exception handler for background threads to log
    // to java.util.logging then exit the JVM with an error code.
    //
    // (We do this because the default is to just print to stderr and not exit the JVM,
    // which is not safe in a multithreaded environment if the thread held a lock or
    // resource which other threads need.)
    Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
      LOG.error(e, "Uncaught exception from thread %s", t);
      if (context.isPresent()) {
        // Shut down the Nailgun server and make sure it stops trapping System.exit().
        //
        // We pass false for exitVM because otherwise Nailgun exits with code 0.
        context.get().getNGServer().shutdown(/* exitVM */ false);
      }
      NON_REENTRANT_SYSTEM_EXIT.shutdownSoon(FAIL_EXIT_CODE);
    });
  }

  public static void main(String[] args) {
    new Main(System.out, System.err, System.in)
        .runMainThenExit(args, Optional.empty(), System.nanoTime());
  }

  private static void markFdCloseOnExec(int fd) {
    int fdFlags;
    fdFlags = Libc.INSTANCE.fcntl(fd, Libc.Constants.rFGETFD);
    if (fdFlags == -1) {
      throw new LastErrorException(Native.getLastError());
    }
    fdFlags |= Libc.Constants.rFDCLOEXEC;
    if (Libc.INSTANCE.fcntl(fd, Libc.Constants.rFSETFD, fdFlags) == -1) {
      throw new LastErrorException(Native.getLastError());
    }
  }

  private static void daemonizeIfPossible() {
    String osName = System.getProperty("os.name");
    Libc.OpenPtyLibrary openPtyLibrary;
    Platform platform = Platform.detect();
    if (platform == Platform.LINUX) {
      Libc.Constants.rTIOCSCTTY = Libc.Constants.LINUX_TIOCSCTTY;
      Libc.Constants.rFDCLOEXEC = Libc.Constants.LINUX_FD_CLOEXEC;
      Libc.Constants.rFGETFD = Libc.Constants.LINUX_F_GETFD;
      Libc.Constants.rFSETFD = Libc.Constants.LINUX_F_SETFD;
      openPtyLibrary = (Libc.OpenPtyLibrary)
          Native.loadLibrary("libutil", Libc.OpenPtyLibrary.class);
    } else if (platform == Platform.MACOS) {
      Libc.Constants.rTIOCSCTTY = Libc.Constants.DARWIN_TIOCSCTTY;
      Libc.Constants.rFDCLOEXEC = Libc.Constants.DARWIN_FD_CLOEXEC;
      Libc.Constants.rFGETFD = Libc.Constants.DARWIN_F_GETFD;
      Libc.Constants.rFSETFD = Libc.Constants.DARWIN_F_SETFD;
      openPtyLibrary = (Libc.OpenPtyLibrary)
          Native.loadLibrary(
              com.sun.jna.Platform.C_LIBRARY_NAME,
              Libc.OpenPtyLibrary.class);
    } else {
      LOG.info("not enabling process killing on nailgun exit: unknown OS %s", osName);
      return;
    }

    // Making ourselves a session leader with setsid disconnects us from our controlling terminal
    int ret = Libc.INSTANCE.setsid();
    if (ret < 0) {
      LOG.warn(
          "cannot enable background process killing: %s",
          Native.getLastError());
      return;
    }

    LOG.info("enabling background process killing for buckd");

    IntByReference master = new IntByReference();
    IntByReference slave = new IntByReference();

    if (openPtyLibrary.openpty(master, slave, Pointer.NULL, Pointer.NULL, Pointer.NULL) != 0) {
      throw new RuntimeException("Failed to open pty");
    }

    // Deliberately leak the file descriptors for the lifetime of this process; NuProcess can
    // sometimes leak file descriptors to children, so make sure these FDs are marked close-on-exec.
    markFdCloseOnExec(master.getValue());
    markFdCloseOnExec(slave.getValue());

    // Make the pty our controlling terminal; works because we disconnected above with setsid.
    if (Libc.INSTANCE.ioctl(
        slave.getValue(),
        Pointer.createConstant(Libc.Constants.rTIOCSCTTY),
        0) == -1) {
      throw new RuntimeException("Failed to set pty");
    }

    LOG.info("enabled background process killing for buckd");
    isSessionLeader = true;

  }

  public static final class DaemonBootstrap {
    private static final int AFTER_COMMAND_AUTO_GC_DELAY_MS = 5000;
    private static @Nullable DaemonKillers daemonKillers;
    private static AtomicReference<ScheduledFuture<?>> scheduledGC = new AtomicReference<>();

    /**
     * Single thread for running short-lived tasks outside the command context.
     */
    private static final ScheduledExecutorService housekeepingExecutorService =
        Executors.newSingleThreadScheduledExecutor();

    public static void main(String[] args) throws Exception {
      try {
        daemonizeIfPossible();
        if (isSessionLeader) {
          BgProcessKiller.init();
          LOG.info("initialized bg session killer");
        }
      } catch (Throwable ex) {
        System.err.println(String.format("buckd: fatal error %s", ex));
        System.exit(1);
      }

      if (args.length != 2) {
        System.err.println("Usage: buckd socketpath heartbeatTimeout");
        return;
      }

      String socketPath = args[0];
      int heartbeatTimeout = Integer.parseInt(args[1]);
      // Strip out optional local: prefix.  This server only use domain sockets.
      if (socketPath.startsWith("local:")) {
        socketPath = socketPath.substring("local:".length());
      }
      NGServer server = new NGServer(
          new NGListeningAddress(socketPath),
          NGServer.DEFAULT_SESSIONPOOLSIZE,
          heartbeatTimeout);
      daemonKillers = new DaemonKillers(housekeepingExecutorService, server, Paths.get(socketPath));
      server.run();
    }

    static DaemonKillers getDaemonKillers() {
      return Preconditions.checkNotNull(daemonKillers, "Daemon killers should be initialized.");
    }

    static void cancelGC() {
      ScheduledFuture<?> oldScheduledGC = scheduledGC.getAndSet(null);
      if (oldScheduledGC != null) {
        oldScheduledGC.cancel(false);
      }
    }

    static void scheduleGC() {
      ScheduledFuture<?> oldScheduledGC = scheduledGC.getAndSet(
          housekeepingExecutorService.schedule(
              System::gc,
              AFTER_COMMAND_AUTO_GC_DELAY_MS,
              TimeUnit.MILLISECONDS));
      if (oldScheduledGC != null) {
        oldScheduledGC.cancel(false);
      }
    }
  }

  private static class DaemonKillers {
    private final NGServer server;
    private final IdleKiller idleKiller;
    private final SocketLossKiller socketLossKiller;

    DaemonKillers(ScheduledExecutorService executorService, NGServer server, Path socketPath) {
      this.server = server;
      this.idleKiller = new IdleKiller(
          executorService,
          DAEMON_SLAYER_TIMEOUT,
          this::killServer);
      this.socketLossKiller = new SocketLossKiller(
          executorService,
          socketPath.toAbsolutePath(),
          this::killServer);
    }

    IdleKiller.CommandExecutionScope newCommandExecutionScope() {
      socketLossKiller.arm();  // Arm the socket loss killer also.
      return idleKiller.newCommandExecutionScope();
    }

    private void killServer() {
      server.shutdown(true);
    }
  }

  /**
   * To prevent 'buck kill' from deleting resources from underneath a 'live' buckd we hold on to
   * the FileLock for the entire lifetime of the process. We depend on the fact that on Linux and
   * MacOS Java FileLock is implemented using the same mechanism as the Python fcntl.lockf method.
   * Should this not be the case we'll simply have a small race between buckd start and `buck kill`.
   */
  private static void obtainResourceFileLock() {
    if (resourcesFileLock != null) {
      return;
    }
    String resourceLockFilePath = System.getProperties().getProperty("buck.resource_lock_path");
    if (resourceLockFilePath == null) {
      // Running from ant, no resource lock needed.
      return;
    }
    try {
      // R+W+A is equivalent to 'a+' in Python (which is how the lock file is opened in Python)
      // because WRITE in Java does not imply truncating the file.
      FileChannel fileChannel = FileChannel.open(
          Paths.get(resourceLockFilePath),
          StandardOpenOption.READ,
          StandardOpenOption.WRITE,
          StandardOpenOption.CREATE);
      resourcesFileLock = fileChannel.tryLock(0L, Long.MAX_VALUE, true);
    } catch (IOException | OverlappingFileLockException  e) {
      LOG.debug(e, "Error when attempting to acquire resources file lock.");
    }
  }

  /**
   * When running as a daemon in the NailGun server, {@link #nailMain(NGContext)} is called instead
   * of {@link #main(String[])} so that the given context can be used to listen for client
   * disconnections and interrupt command processing when they occur.
   */
  public static void nailMain(final NGContext context) throws InterruptedException {
    obtainResourceFileLock();
    try (IdleKiller.CommandExecutionScope ignored =
             DaemonBootstrap.getDaemonKillers().newCommandExecutionScope()) {
      DaemonBootstrap.cancelGC();
      new Main(context.out, context.err, context.in)
          .runMainThenExit(context.getArgs(), Optional.of(context), System.nanoTime());
    } finally {
      // Reclaim memory after a command finishes.
      DaemonBootstrap.scheduleGC();
    }
  }
}
