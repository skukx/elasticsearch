/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.test;

import com.carrotsearch.randomizedtesting.generators.RandomStrings;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;

import org.apache.lucene.store.MockDirectoryWrapper;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TestUtil;
import org.elasticsearch.Version;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.routing.DjbHashFunction;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsAbortPolicy;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.test.cache.recycler.MockBigArrays;
import org.elasticsearch.test.cache.recycler.MockPageCacheRecycler;
import org.elasticsearch.test.search.MockSearchService;
import org.elasticsearch.test.store.MockDirectoryHelper;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.*;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAllFilesClosed;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAllSearchersClosed;

/**
 * Base testcase for randomized unit testing with Elasticsearch
 */
@LuceneTestCase.SuppressFileSystems("*")
public abstract class ElasticsearchTestCase extends ESTestCase {

    private static Thread.UncaughtExceptionHandler defaultHandler;

    protected final ESLogger logger = Loggers.getLogger(getClass());

    /**
     * Property that allows to adapt the tests behaviour to older features/bugs based on the input version
     */
    private static final String TESTS_COMPATIBILITY = "tests.compatibility";

    private static final Version GLOABL_COMPATIBILITY_VERSION = Version.fromString(compatibilityVersionProperty());

    static {
        SecurityHack.ensureInitialized();
    }

    /**
     * Runs the code block for 10 seconds waiting for no assertion to trip.
     */
    public static void assertBusy(Runnable codeBlock) throws Exception {
        assertBusy(Executors.callable(codeBlock), 10, TimeUnit.SECONDS);
    }

    public static void assertBusy(Runnable codeBlock, long maxWaitTime, TimeUnit unit) throws Exception {
        assertBusy(Executors.callable(codeBlock), maxWaitTime, unit);
    }

    /**
     * Runs the code block for 10 seconds waiting for no assertion to trip.
     */
    public static <V> V assertBusy(Callable<V> codeBlock) throws Exception {
        return assertBusy(codeBlock, 10, TimeUnit.SECONDS);
    }

    /**
     * Runs the code block for the provided interval, waiting for no assertions to trip.
     */
    public static <V> V assertBusy(Callable<V> codeBlock, long maxWaitTime, TimeUnit unit) throws Exception {
        long maxTimeInMillis = TimeUnit.MILLISECONDS.convert(maxWaitTime, unit);
        long iterations = Math.max(Math.round(Math.log10(maxTimeInMillis) / Math.log10(2)), 1);
        long timeInMillis = 1;
        long sum = 0;
        List<AssertionError> failures = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            try {
                return codeBlock.call();
            } catch (AssertionError e) {
                failures.add(e);
            }
            sum += timeInMillis;
            Thread.sleep(timeInMillis);
            timeInMillis *= 2;
        }
        timeInMillis = maxTimeInMillis - sum;
        Thread.sleep(Math.max(timeInMillis, 0));
        try {
            return codeBlock.call();
        } catch (AssertionError e) {
            for (AssertionError failure : failures) {
                e.addSuppressed(failure);
            }
            throw e;
        }
    }


    public static boolean awaitBusy(Predicate<?> breakPredicate) throws InterruptedException {
        return awaitBusy(breakPredicate, 10, TimeUnit.SECONDS);
    }

    public static boolean awaitBusy(Predicate<?> breakPredicate, long maxWaitTime, TimeUnit unit) throws InterruptedException {
        long maxTimeInMillis = TimeUnit.MILLISECONDS.convert(maxWaitTime, unit);
        long iterations = Math.max(Math.round(Math.log10(maxTimeInMillis) / Math.log10(2)), 1);
        long timeInMillis = 1;
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            if (breakPredicate.apply(null)) {
                return true;
            }
            sum += timeInMillis;
            Thread.sleep(timeInMillis);
            timeInMillis *= 2;
        }
        timeInMillis = maxTimeInMillis - sum;
        Thread.sleep(Math.max(timeInMillis, 0));
        return breakPredicate.apply(null);
    }

    private static final String[] numericTypes = new String[]{"byte", "short", "integer", "long"};

    public static String randomNumericType(Random random) {
        return numericTypes[random.nextInt(numericTypes.length)];
    }

    /**
     * Returns a {@link java.nio.file.Path} pointing to the class path relative resource given
     * as the first argument. In contrast to
     * <code>getClass().getResource(...).getFile()</code> this method will not
     * return URL encoded paths if the parent path contains spaces or other
     * non-standard characters.
     */
    public Path getResourcePath(String relativePath) {
        URI uri = URI.create(getClass().getResource(relativePath).toString());
        return PathUtils.get(uri);
    }

    @After
    public void ensureAllPagesReleased() throws Exception {
        MockPageCacheRecycler.ensureAllPagesAreReleased();
    }

    @After
    public void ensureAllArraysReleased() throws Exception {
        MockBigArrays.ensureAllArraysAreReleased();
    }

    @After
    public void ensureAllSearchContextsReleased() throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                MockSearchService.assertNoInFLightContext();
            }
        });
    }

    public static boolean hasUnclosedWrapper() {
        for (MockDirectoryWrapper w : MockDirectoryHelper.wrappers) {
            if (w.isOpen()) {
                return true;
            }
        }
        return false;
    }

    @BeforeClass
    public static void setBeforeClass() throws Exception {
        closeAfterSuite(new Closeable() {
            @Override
            public void close() throws IOException {
                assertAllFilesClosed();
            }
        });
        closeAfterSuite(new Closeable() {
            @Override
            public void close() throws IOException {
                assertAllSearchersClosed();
            }
        });
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new ElasticsearchUncaughtExceptionHandler(defaultHandler));
        Requests.CONTENT_TYPE = randomXContentType();
        Requests.INDEX_CONTENT_TYPE = randomXContentType();
    }

    public static XContentType randomXContentType() {
        return randomFrom(XContentType.values());
    }

    @AfterClass
    public static void resetAfterClass() {
        Thread.setDefaultUncaughtExceptionHandler(defaultHandler);
        Requests.CONTENT_TYPE = XContentType.SMILE;
        Requests.INDEX_CONTENT_TYPE = XContentType.JSON;
    }

    public static boolean maybeDocValues() {
        return random().nextBoolean();
    }

    private static final List<Version> SORTED_VERSIONS;

    static {
        Field[] declaredFields = Version.class.getDeclaredFields();
        Set<Integer> ids = new HashSet<>();
        for (Field field : declaredFields) {
            final int mod = field.getModifiers();
            if (Modifier.isStatic(mod) && Modifier.isFinal(mod) && Modifier.isPublic(mod)) {
                if (field.getType() == Version.class) {
                    try {
                        Version object = (Version) field.get(null);
                        ids.add(object.id);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        List<Integer> idList = new ArrayList<>(ids);
        Collections.sort(idList);
        Collections.reverse(idList);
        ImmutableList.Builder<Version> version = ImmutableList.builder();
        for (Integer integer : idList) {
            version.add(Version.fromId(integer));
        }
        SORTED_VERSIONS = version.build();
    }

    /**
     * @return the {@link Version} before the {@link Version#CURRENT}
     */
    public static Version getPreviousVersion() {
        Version version = SORTED_VERSIONS.get(1);
        assert version.before(Version.CURRENT);
        return version;
    }
    
    /**
     * A random {@link Version}.
     *
     * @return a random {@link Version} from all available versions
     */
    public static Version randomVersion() {
        return randomVersion(random());
    }
    
    /**
     * A random {@link Version}.
     * 
     * @param random
     *            the {@link Random} to use to generate the random version
     *
     * @return a random {@link Version} from all available versions
     */
    public static Version randomVersion(Random random) {
        return SORTED_VERSIONS.get(random.nextInt(SORTED_VERSIONS.size()));
    }
    
    /**
     * Returns immutable list of all known versions.
     */
    public static List<Version> allVersions() {
        return Collections.unmodifiableList(SORTED_VERSIONS);
    }

    /**
     * A random {@link Version} from <code>minVersion</code> to
     * <code>maxVersion</code> (inclusive).
     * 
     * @param minVersion
     *            the minimum version (inclusive)
     * @param maxVersion
     *            the maximum version (inclusive)
     * @return a random {@link Version} from <code>minVersion</code> to
     *         <code>maxVersion</code> (inclusive)
     */
    public static Version randomVersionBetween(Version minVersion, Version maxVersion) {
        return randomVersionBetween(random(), minVersion, maxVersion);
    }

    /**
     * A random {@link Version} from <code>minVersion</code> to
     * <code>maxVersion</code> (inclusive).
     * 
     * @param random
     *            the {@link Random} to use to generate the random version
     * @param minVersion
     *            the minimum version (inclusive)
     * @param maxVersion
     *            the maximum version (inclusive)
     * @return a random {@link Version} from <code>minVersion</code> to
     *         <code>maxVersion</code> (inclusive)
     */
    public static Version randomVersionBetween(Random random, Version minVersion, Version maxVersion) {
        int minVersionIndex = SORTED_VERSIONS.size();
        if (minVersion != null) {
            minVersionIndex = SORTED_VERSIONS.indexOf(minVersion);
        }
        int maxVersionIndex = 0;
        if (maxVersion != null) {
            maxVersionIndex = SORTED_VERSIONS.indexOf(maxVersion);
        }
        if (minVersionIndex == -1) {
            throw new IllegalArgumentException("minVersion [" + minVersion + "] does not exist.");
        } else if (maxVersionIndex == -1) {
            throw new IllegalArgumentException("maxVersion [" + maxVersion + "] does not exist.");
        } else {
            // minVersionIndex is inclusive so need to add 1 to this index
            int range = minVersionIndex + 1 - maxVersionIndex;
            return SORTED_VERSIONS.get(maxVersionIndex + random.nextInt(range));
        }
    }

    /**
     * Return consistent index settings for the provided index version.
     */
    public static ImmutableSettings.Builder settings(Version version) {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, version);
        if (version.before(Version.V_2_0_0)) {
            builder.put(IndexMetaData.SETTING_LEGACY_ROUTING_HASH_FUNCTION, DjbHashFunction.class);
        }
        return builder;
    }

    static final class ElasticsearchUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

        private final Thread.UncaughtExceptionHandler parent;
        private final ESLogger logger = Loggers.getLogger(getClass());

        private ElasticsearchUncaughtExceptionHandler(Thread.UncaughtExceptionHandler parent) {
            this.parent = parent;
        }


        @Override
        public void uncaughtException(Thread t, Throwable e) {
            if (e instanceof EsRejectedExecutionException) {
                if (e.getMessage().contains(EsAbortPolicy.SHUTTING_DOWN_KEY)) {
                    return; // ignore the EsRejectedExecutionException when a node shuts down
                }
            } else if (e instanceof OutOfMemoryError) {
                if (e.getMessage().contains("unable to create new native thread")) {
                    printStackDump(logger);
                }
            }
            parent.uncaughtException(t, e);
        }

    }

    protected static final void printStackDump(ESLogger logger) {
        // print stack traces if we can't create any native thread anymore
        Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();
        logger.error(formatThreadStacks(allStackTraces));
    }

    /**
     * Dump threads and their current stack trace.
     */
    private static String formatThreadStacks(Map<Thread, StackTraceElement[]> threads) {
        StringBuilder message = new StringBuilder();
        int cnt = 1;
        final Formatter f = new Formatter(message, Locale.ENGLISH);
        for (Map.Entry<Thread, StackTraceElement[]> e : threads.entrySet()) {
            if (e.getKey().isAlive())
                f.format(Locale.ENGLISH, "\n  %2d) %s", cnt++, threadName(e.getKey())).flush();
            if (e.getValue().length == 0) {
                message.append("\n        at (empty stack)");
            } else {
                for (StackTraceElement ste : e.getValue()) {
                    message.append("\n        at ").append(ste);
                }
            }
        }
        return message.toString();
    }

    private static String threadName(Thread t) {
        return "Thread[" +
                "id=" + t.getId() +
                ", name=" + t.getName() +
                ", state=" + t.getState() +
                ", group=" + groupName(t.getThreadGroup()) +
                "]";
    }

    private static String groupName(ThreadGroup threadGroup) {
        if (threadGroup == null) {
            return "{null group}";
        } else {
            return threadGroup.getName();
        }
    }

    public static String[] generateRandomStringArray(int maxArraySize, int maxStringSize, boolean allowNull) {
        if (allowNull && random().nextBoolean()) {
            return null;
        }
        String[] array = new String[random().nextInt(maxArraySize)]; // allow empty arrays
        for (int i = 0; i < array.length; i++) {
            array[i] = RandomStrings.randomAsciiOfLength(random(), maxStringSize);
        }
        return array;
    }

    public static String[] generateRandomStringArray(int maxArraySize, int maxStringSize) {
        return generateRandomStringArray(maxArraySize, maxStringSize, false);
    }


    /**
     * If a test is annotated with {@link org.elasticsearch.test.ElasticsearchTestCase.CompatibilityVersion}
     * all randomized settings will only contain settings or mappings which are compatible with the specified version ID.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE})
    @Ignore
    public @interface CompatibilityVersion {
        int version();
    }

    /**
     * Returns a global compatibility version that is set via the
     * {@value #TESTS_COMPATIBILITY} or {@value #TESTS_BACKWARDS_COMPATIBILITY_VERSION} system property.
     * If both are unset the current version is used as the global compatibility version. This
     * compatibility version is used for static randomization. For per-suite compatibility version see
     * {@link #compatibilityVersion()}
     */
    public static Version globalCompatibilityVersion() {
        return GLOABL_COMPATIBILITY_VERSION;
    }

    /**
     * Retruns the tests compatibility version.
     */
    public Version compatibilityVersion() {
        return compatibilityVersion(getClass());
    }

    private Version compatibilityVersion(Class<?> clazz) {
        if (clazz == Object.class || clazz == ElasticsearchIntegrationTest.class) {
            return globalCompatibilityVersion();
        }
        CompatibilityVersion annotation = clazz.getAnnotation(CompatibilityVersion.class);
        if (annotation != null) {
            return Version.smallest(Version.fromId(annotation.version()), compatibilityVersion(clazz.getSuperclass()));
        }
        return compatibilityVersion(clazz.getSuperclass());
    }

    private static String compatibilityVersionProperty() {
        final String version = System.getProperty(TESTS_COMPATIBILITY);
        if (Strings.hasLength(version)) {
            return version;
        }
        return System.getProperty(TESTS_BACKWARDS_COMPATIBILITY_VERSION);
    }


    public static boolean terminate(ExecutorService... services) throws InterruptedException {
        boolean terminated = true;
        for (ExecutorService service : services) {
            if (service != null) {
                terminated &= ThreadPool.terminate(service, 10, TimeUnit.SECONDS);
            }
        }
        return terminated;
    }

    public static boolean terminate(ThreadPool service) throws InterruptedException {
        return ThreadPool.terminate(service, 10, TimeUnit.SECONDS);
    }
    
    // TODO: these method names stink, but are a temporary solution.
    // see https://github.com/carrotsearch/randomizedtesting/pull/178

    /**
     * Returns a temporary file
     * @throws IOException 
     */
    public Path newTempFilePath() throws IOException {
        return createTempFile();
    }
    
    /**
     * Returns a temporary directory
     */
    public Path newTempDirPath() {
        return createTempDir();
    }

    /**
     * Returns a random number of temporary paths.
     */
    public String[] tmpPaths() {
        final int numPaths = TestUtil.nextInt(random(), 1, 3);
        final String[] absPaths = new String[numPaths];
        for (int i = 0; i < numPaths; i++) {
            absPaths[i] = newTempDirPath().toAbsolutePath().toString();
        }
        return absPaths;
    }

    public NodeEnvironment newNodeEnvironment() throws IOException {
        return newNodeEnvironment(ImmutableSettings.EMPTY);
    }

    public NodeEnvironment newNodeEnvironment(Settings settings) throws IOException {
        Settings build = ImmutableSettings.builder()
                .put(settings)
                .put("path.home", newTempDirPath().toAbsolutePath())
                .putArray("path.data", tmpPaths()).build();
        return new NodeEnvironment(build, new Environment(build));
    }

}
