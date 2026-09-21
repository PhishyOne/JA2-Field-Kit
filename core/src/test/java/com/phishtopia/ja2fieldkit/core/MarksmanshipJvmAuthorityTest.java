package com.phishtopia.ja2fieldkit.core;

import org.junit.jupiter.api.Test;
import javax.tools.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Includes every synthetic/default/bridge member: JVM access is the authority. */
public class MarksmanshipJvmAuthorityTest {
    private static Class<?> implementation() {
        return MarksmanshipEditResult.VerifiedCandidate.class.getPermittedSubclasses()[0];
    }

    @Test
    public void publicSurfaceHasNoAuthorityBearingRouteIncludingSyntheticMembers() {
        assertEquals(1, Ja2MarksmanshipEditor.class.getConstructors().length);
        assertEquals(0, Ja2MarksmanshipEditor.class.getConstructors()[0].getParameterCount());
        assertTrue(Modifier.isFinal(Ja2MarksmanshipEditor.class.getModifiers()));
        Set<String> publicEditorMethods = new HashSet<>();
        for (Method method : Ja2MarksmanshipEditor.class.getMethods()) {
            if (method.getDeclaringClass() != Object.class) publicEditorMethods.add(method.getName());
        }
        assertEquals(Set.of("edit"), publicEditorMethods);
        var types = new ArrayList<Class<?>>();
        collectNested(Ja2MarksmanshipEditor.class, types);
        collectNested(MarksmanshipEditResult.class, types);
        collectNested(SyntheticMarksmanshipCapability.class, types);
        for (Class<?> type : types) {
            for (Constructor<?> ctor : type.getConstructors()) {
                assertFalse(Arrays.asList(ctor.getParameterTypes()).contains(SyntheticMarksmanshipCapability.class), ctor.toString());
            }
            for (Method method : type.getMethods()) {
                assertFalse(Arrays.asList(method.getParameterTypes()).contains(SyntheticMarksmanshipCapability.class), method.toString());
                assertFalse(MarksmanshipEditResult.VerifiedCandidate.class.isAssignableFrom(method.getReturnType()), method.toString());
            }
        }
        assertEquals(0, implementation().getConstructors().length);
        assertFalse(Modifier.isPublic(implementation().getModifiers()));
        assertEquals(1, implementation().getDeclaredConstructors().length);
        assertTrue(Modifier.isPrivate(implementation().getDeclaredConstructors()[0].getModifiers()));
        assertEquals(Set.of("getCandidateBytes", "getProvenance", "getVerification"),
                Arrays.stream(implementation().getDeclaredMethods()).filter(m -> Modifier.isPublic(m.getModifiers()))
                        .map(Method::getName).collect(java.util.stream.Collectors.toSet()));
        for (Method method : Ja2MarksmanshipEditor.class.getDeclaredMethods()) {
            if (!method.getName().equals("edit")) assertTrue(Modifier.isPrivate(method.getModifiers()), method.toString());
        }
    }

    private static void collectNested(Class<?> type, List<Class<?>> types) {
        types.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) collectNested(nested, types);
    }

    @Test
    public void sealingPermitsExactlyTheFinalPrivateConstructionImplementation() {
        assertTrue(MarksmanshipEditResult.class.isSealed());
        assertEquals(Set.of(MarksmanshipEditResult.Failure.class, MarksmanshipEditResult.VerifiedCandidate.class),
                Set.of(MarksmanshipEditResult.class.getPermittedSubclasses()));
        assertTrue(MarksmanshipEditResult.VerifiedCandidate.class.isSealed());
        assertEquals(1, MarksmanshipEditResult.VerifiedCandidate.class.getPermittedSubclasses().length);
        assertEquals(Ja2MarksmanshipEditor.class.getName() + "$VerifiedCandidateImpl", implementation().getName());
        assertTrue(Modifier.isFinal(implementation().getModifiers()));
        assertTrue(Modifier.isFinal(MarksmanshipEditResult.Failure.class.getModifiers()));
        assertEquals(Ja2MarksmanshipEditor.class, implementation().getNestHost());
    }

    @Test
    public void previousOrdinaryReflectionExploitsFailWithoutAccessSuppression() throws Exception {
        var ctor = Ja2MarksmanshipEditor.class.getDeclaredConstructor(Ja2SaveInspector.class,
                SyntheticMarksmanshipCapability.class, Consumer.class);
        assertThrows(IllegalAccessException.class, () -> ctor.newInstance(new Ja2SaveInspector(),
                new SyntheticMarksmanshipCapability(16_777_216, 16_777_216), (Consumer<EditWork>) work -> {}));
        var successCtor = implementation().getDeclaredConstructors()[0];
        assertThrows(IllegalAccessException.class, () -> successCtor.newInstance(new byte[0], null));
        assertThrows(NoSuchFieldException.class, () -> MarksmanshipEditResult.VerifiedCandidate.class.getField("Companion"));
        assertThrows(NoSuchMethodException.class, () -> Ja2MarksmanshipEditor.class.getMethod(
                "transact", byte[].class, MarksmanshipEditRequest.class));
        var transact = Ja2MarksmanshipEditor.class.getDeclaredMethod("transact", byte[].class, MarksmanshipEditRequest.class);
        assertThrows(IllegalAccessException.class, () -> transact.invoke(new Ja2MarksmanshipEditor(), new byte[0],
                new MarksmanshipEditRequest(0, 0, 0)));
        var continuation = Arrays.stream(Ja2MarksmanshipEditor.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("validateCandidate")).findFirst().orElseThrow();
        assertThrows(IllegalAccessException.class, () -> continuation.invoke(new Ja2MarksmanshipEditor(),
                new byte[0], new byte[0], new MarksmanshipEditRequest(0, 0, 0), null, List.of(), null, ""));
        var failure = assertInstanceOf(MarksmanshipEditResult.Failure.class,
                new Ja2MarksmanshipEditor().edit(new byte[0], new MarksmanshipEditRequest(0, 0, 0)));
        assertEquals(MarksmanshipEditReason.CAPABILITY_DISABLED, failure.getReason());
    }

    @Test
    public void adversarialJavaCannotCompileEvenInTheCorePackage() throws Exception {
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "JDK 21 compiler is required");
        List<String> attacks = List.of(
                "class Probe { Object x = new Ja2MarksmanshipEditor(new Ja2SaveInspector(), new SyntheticMarksmanshipCapability(1, 1), w -> {}); }",
                "class Probe { Object x = new Ja2MarksmanshipEditor().transact(new byte[0], new MarksmanshipEditRequest(0,0,0)); }",
                "class Probe { Object x = MarksmanshipEditResult.VerifiedCandidate.Companion.transact(new byte[0], new MarksmanshipEditRequest(0,0,0), new Ja2SaveInspector(), new SyntheticMarksmanshipCapability(1,1), null); }",
                "class Probe { Object x = new Ja2MarksmanshipEditor.VerifiedCandidateImpl(new byte[0], null); }",
                "class Probe { Object x = new MarksmanshipEditResult.VerifiedCandidate(new byte[0], null); }",
                "abstract class Probe implements MarksmanshipEditResult.VerifiedCandidate {}",
                "abstract class Probe implements MarksmanshipEditResult {}",
                "class Probe { Object x = Ja2MarksmanshipEditor.withSyntheticCapabilityForTesting(null); }"
        );
        for (String pkg : List.of("com.phishtopia.ja2fieldkit.core", "adversary")) {
            // A successful control ensures failure is access/sealing, not a broken compiler classpath.
            assertTrue(compile(pkg, "class Probe { MarksmanshipEditResult x = new Ja2MarksmanshipEditor().edit(new byte[0], new MarksmanshipEditRequest(0,0,0)); byte[] read(MarksmanshipEditResult.VerifiedCandidate v) { return v.getCandidateBytes(); } }"));
            for (String attack : attacks) assertFalse(compile(pkg, attack), pkg + ": " + attack);
        }
    }

    private static boolean compile(String pkg, String body) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var source = new SimpleJavaFileObject(URI.create("string:///" + pkg.replace('.', '/') + "/Probe.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "package " + pkg + "; import com.phishtopia.ja2fieldkit.core.*; " + body;
            }
        };
        Set<String> paths = new LinkedHashSet<>(List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        // Gradle workers may hide test/runtime entries from java.class.path.
        for (Class<?> type : List.of(Ja2MarksmanshipEditor.class, MarksmanshipEditRequest.class, kotlin.Unit.class)) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        Path output = Files.createTempDirectory("marksmanship-javac-");
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            return compiler.getTask(null, files, diagnostics, List.of("--release", "21", "-proc:none", "-classpath",
                    String.join(java.io.File.pathSeparator, paths), "-d", output.toString()), null, List.of(source)).call();
        } finally {
            try (var files = Files.walk(output)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
