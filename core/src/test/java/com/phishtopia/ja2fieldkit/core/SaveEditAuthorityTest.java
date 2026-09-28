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

public class SaveEditAuthorityTest {
    @Test public void closedJvmAuthorityAndImmutableRequest() throws Exception {
        assertTrue(SaveEditResult.class.isSealed());
        assertTrue(SaveEditResult.VerifiedCandidate.class.isSealed());
        assertTrue(SaveEditRequest.Operation.class.isSealed());
        assertEquals(Set.of(SaveEditRequest.SetHiredStat.class), Set.of(SaveEditRequest.Operation.class.getPermittedSubclasses()));
        var impl = SaveEditResult.VerifiedCandidate.class.getPermittedSubclasses();
        assertEquals(1, impl.length);
        assertEquals(Ja2SaveEditor.class, impl[0].getNestHost());
        assertTrue(Modifier.isFinal(impl[0].getModifiers()));
        for (var ctor : impl[0].getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(ctor.getModifiers()));
            assertThrows(IllegalAccessException.class, () -> ctor.newInstance(new byte[0], null));
        }
        assertEquals(1, Ja2SaveEditor.class.getConstructors().length);
        assertEquals(0, Ja2SaveEditor.class.getConstructors()[0].getParameterCount());
        for (var method : Ja2SaveEditor.class.getDeclaredMethods()) {
            if (!method.getName().equals("edit")) assertTrue(Modifier.isPrivate(method.getModifiers()), method.toString());
        }
        var constructor = Ja2SaveEditor.class.getDeclaredConstructor(Ja2SaveInspector.class, SyntheticSaveEditCapability.class, Consumer.class);
        assertThrows(IllegalAccessException.class, () -> constructor.newInstance(new Ja2SaveInspector(),
            new SyntheticSaveEditCapability(1, 1), (Consumer<EditWork>) work -> {}));
        for (var type : List.of(SaveEditRequest.class, SaveEditRequest.SourceIdentity.class, SaveEditRequest.SetHiredStat.class)) {
            assertTrue(type.isRecord());
            for (var field : type.getDeclaredFields()) assertTrue(Modifier.isFinal(field.getModifiers()));
        }
        for (var field : SaveEditResult.Failure.class.getDeclaredFields()) assertTrue(field.getType().isEnum());
        assertFalse(Arrays.stream(SaveEditResult.Failure.class.getMethods()).anyMatch(m -> m.getReturnType() == byte[].class));
    }

    @Test public void adversarialCompilationCannotMintSuccessOrSupplyCapabilities() throws Exception {
        var attacks = List.of(
            "class Probe { Object x = new Ja2SaveEditor(new Ja2SaveInspector(), new SyntheticSaveEditCapability(1,1), w -> {}); }",
            "class Probe { Object x = new Ja2SaveEditor.VerifiedCandidateImpl(new byte[0], null); }",
            "abstract class Probe implements SaveEditResult.VerifiedCandidate {}",
            "abstract class Probe implements SaveEditResult {}",
            "abstract class Probe implements SaveEditRequest.Operation {}",
            "class Probe { Object x = new Ja2SaveEditor.BoundedCandidateWriter(1,1, () -> {}); }"
        );
        for (var pkg : List.of("com.phishtopia.ja2fieldkit.core", "adversary")) {
            assertTrue(compile(pkg, "class Probe { SaveEditResult x = new Ja2SaveEditor().edit(new byte[0], new SaveEditRequest(new SaveEditRequest.SourceIdentity(0, \"0\".repeat(64)), new SaveEditRequest.SetHiredStat(0,HiredMercStat.MARKSMANSHIP,0,0))); }"));
            for (var attack : attacks) assertFalse(compile(pkg, attack), attack);
        }
    }

    @Test public void boundedWriterChecksBeforeAllocationAndFailsTerminally() throws Exception {
        var type = Arrays.stream(Ja2SaveEditor.class.getDeclaredClasses()).filter(t -> t.getSimpleName().equals("BoundedCandidateWriter")).findFirst().orElseThrow();
        var ctor = type.getDeclaredConstructor(int.class, int.class, Runnable.class);
        var append = type.getDeclaredMethod("append", byte[].class, int.class, int.class);
        var finish = type.getDeclaredMethod("finish");
        ctor.setAccessible(true); append.setAccessible(true); finish.setAccessible(true);
        int[] allocations = {0};
        for (int[] bounds : List.of(new int[]{-1, 2}, new int[]{3, 2}, new int[]{16_777_217, Integer.MAX_VALUE})) {
            assertThrows(InvocationTargetException.class, () -> ctor.newInstance(bounds[0], bounds[1], (Runnable) () -> allocations[0]++));
        }
        assertEquals(0, allocations[0]);
        var exact = ctor.newInstance(2, 2, (Runnable) () -> {});
        append.invoke(exact, new byte[]{1,2}, 0, 2);
        assertArrayEquals(new byte[]{1,2}, (byte[]) finish.invoke(exact));
        assertThrows(InvocationTargetException.class, () -> finish.invoke(exact));
        var overflow = ctor.newInstance(2, 16_777_216, (Runnable) () -> {});
        append.invoke(overflow, new byte[]{1,2}, 0, 2);
        assertThrows(InvocationTargetException.class, () -> append.invoke(overflow, new byte[]{3}, 0, 1));
        assertThrows(InvocationTargetException.class, () -> finish.invoke(overflow));
        var underfill = ctor.newInstance(2, 2, (Runnable) () -> {});
        assertThrows(InvocationTargetException.class, () -> finish.invoke(underfill));
        assertThrows(InvocationTargetException.class, () -> append.invoke(underfill, new byte[]{1,2}, 0, 2));
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
        for (Class<?> type : List.of(Ja2SaveEditor.class, SaveEditRequest.class, kotlin.Unit.class)) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        Path output = Files.createTempDirectory("save-edit-javac-");
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
