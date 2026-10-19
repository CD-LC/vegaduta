package com.agenticai.core.apidocs;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.asm.Type;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the 2xx status a handler sets in its method body, which reflection on annotations cannot
 * see: {@code ResponseEntity.created(..)}, {@code accepted()}, {@code noContent()}, {@code ok(..)},
 * {@code ResponseEntity.status(HttpStatus.X)} / {@code status(201)}, {@code new
 * ResponseEntity<>(body, HttpStatus.X)} and {@code HttpServletResponse.setStatus(201)}. It reads
 * the controller's bytecode with Spring's repackaged ASM (no new dependency) and follows lambdas
 * and same-class helpers that the handler calls (for example {@code mono.map(x ->
 * ResponseEntity.status(CREATED).body(x))}).
 *
 * <p>The answer is conservative: a status is reported only when every success path found sets the
 * same constant 2xx status. A status taken from a variable, two different 2xx statuses, or no
 * status call at all yields empty, and the generator keeps its default.
 */
final class OpenApiBodyStatusScanner {

    private static final String RESPONSE_ENTITY = "org/springframework/http/ResponseEntity";
    private static final String HTTP_STATUS = "org/springframework/http/HttpStatus";
    private static final String HTTP_STATUS_CODE = "org/springframework/http/HttpStatusCode";
    private static final Set<String> SERVLET_RESPONSES =
            Set.of(
                    "jakarta/servlet/http/HttpServletResponse",
                    "jakarta/servlet/http/HttpServletResponseWrapper");
    private static final int MAX_DEPTH = 4;

    /** What one method body says about statuses, before following calls. */
    private static final class Facts {
        final Set<Integer> statuses = new TreeSet<>();
        boolean dynamic;
        final List<String> callees = new ArrayList<>();
    }

    private final Map<Class<?>, Map<String, Facts>> cache = new HashMap<>();

    /** The single constant 2xx status the handler's body sets, if there is exactly one. */
    Optional<Integer> successStatus(Method method) {
        Class<?> owner = method.getDeclaringClass();
        Map<String, Facts> byMethod = factsOf(owner);
        if (byMethod.isEmpty()) {
            return Optional.empty();
        }
        Set<Integer> statuses = new TreeSet<>();
        boolean[] dynamic = {false};
        collect(
                byMethod,
                method.getName() + Type.getMethodDescriptor(method),
                statuses,
                dynamic,
                new HashSet<>(),
                0);
        if (dynamic[0] || statuses.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(statuses.iterator().next());
    }

    private static void collect(
            Map<String, Facts> byMethod,
            String key,
            Set<Integer> statuses,
            boolean[] dynamic,
            Set<String> seen,
            int depth) {
        Facts facts = byMethod.get(key);
        if (facts == null || !seen.add(key) || depth > MAX_DEPTH) {
            return;
        }
        statuses.addAll(facts.statuses);
        dynamic[0] |= facts.dynamic;
        for (String callee : facts.callees) {
            collect(byMethod, callee, statuses, dynamic, seen, depth + 1);
        }
    }

    private Map<String, Facts> factsOf(Class<?> type) {
        return cache.computeIfAbsent(type, OpenApiBodyStatusScanner::read);
    }

    private static Map<String, Facts> read(Class<?> type) {
        String internal = type.getName().replace('.', '/');
        ClassLoader loader =
                type.getClassLoader() != null
                        ? type.getClassLoader()
                        : ClassLoader.getSystemClassLoader();
        Map<String, Facts> out = new HashMap<>();
        try (InputStream in = loader.getResourceAsStream(internal + ".class")) {
            if (in == null) {
                return out;
            }
            new ClassReader(in).accept(new Collector(internal, out), ClassReader.SKIP_FRAMES);
        } catch (IOException | RuntimeException e) {
            // Unreadable bytecode: the generator keeps its default status.
            out.clear();
        }
        return out;
    }

    private static final class Collector extends ClassVisitor {
        private final String owner;
        private final Map<String, Facts> out;

        Collector(String owner, Map<String, Facts> out) {
            super(SpringAsmInfo.ASM_VERSION);
            this.owner = owner;
            this.out = out;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] ex) {
            Facts facts = new Facts();
            out.put(name + descriptor, facts);
            return new BodyVisitor(owner, facts);
        }
    }

    /**
     * Tracks the constant status pushed by the instruction right before a status-taking call. Any
     * other instruction clears it; labels, line numbers and frames are not instructions.
     */
    private static final class BodyVisitor extends MethodVisitor {
        private final String owner;
        private final Facts facts;
        private Integer pending;

        BodyVisitor(String owner, Facts facts) {
            super(SpringAsmInfo.ASM_VERSION);
            this.owner = owner;
            this.facts = facts;
        }

        /** A status-taking call: a constant argument counts, anything else is unknowable. */
        private void statusArgument(Integer arg) {
            if (arg == null) {
                facts.dynamic = true;
            } else {
                add(arg);
            }
        }

        /** A ResponseEntity factory or constructor, called directly or as a method reference. */
        private void entityCall(String name, String desc, boolean isStatic, Integer arg) {
            switch (name) {
                case "ok", "of", "ofNullable" -> {
                    if (isStatic) {
                        add(200);
                    }
                }
                case "created" -> add(201);
                case "accepted" -> add(202);
                case "noContent" -> add(204);
                case "status" -> statusArgument(arg);
                case "<init>" -> {
                    if (desc.endsWith("HttpStatusCode;)V") || desc.endsWith("I)V")) {
                        statusArgument(arg);
                    }
                }
                default -> {
                    // notFound(), badRequest(), unprocessableEntity(), ...: not 2xx
                }
            }
        }

        private void add(int status) {
            if (status >= 200 && status < 300) {
                facts.statuses.add(status);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String fieldOwner, String name, String desc) {
            pending = null;
            if (opcode == Opcodes.GETSTATIC && HTTP_STATUS.equals(fieldOwner)) {
                try {
                    pending = HttpStatus.valueOf(name).value();
                } catch (IllegalArgumentException ignored) {
                    // not a status constant
                }
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            pending =
                    opcode == Opcodes.SIPUSH || opcode == Opcodes.BIPUSH ? operand : null;
        }

        @Override
        public void visitLdcInsn(Object value) {
            pending = value instanceof Integer i ? i : null;
        }

        @Override
        public void visitMethodInsn(
                int opcode, String callOwner, String name, String desc, boolean itf) {
            Integer arg = pending;
            pending = null;
            if (RESPONSE_ENTITY.equals(callOwner)) {
                entityCall(name, desc, opcode == Opcodes.INVOKESTATIC, arg);
                return;
            }
            if ((HTTP_STATUS_CODE.equals(callOwner) || HTTP_STATUS.equals(callOwner))
                    && "valueOf".equals(name)
                    && desc.startsWith("(I)")) {
                pending = arg; // HttpStatusCode.valueOf(201) passes the constant through
                return;
            }
            if (SERVLET_RESPONSES.contains(callOwner) && "setStatus".equals(name)) {
                statusArgument(arg);
                return;
            }
            if (owner.equals(callOwner)
                    && Type.getReturnType(desc).getDescriptor().equals("L" + RESPONSE_ENTITY + ";")) {
                facts.callees.add(name + desc);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name, String desc, Handle bsm, Object... bsmArgs) {
            pending = null;
            for (Object a : bsmArgs) {
                if (!(a instanceof Handle h)) {
                    continue;
                }
                if (owner.equals(h.getOwner())) {
                    facts.callees.add(h.getName() + h.getDesc()); // lambda body or own method ref
                } else if (RESPONSE_ENTITY.equals(h.getOwner())) {
                    // ResponseEntity::ok, ResponseEntity::status, ResponseEntity::new
                    entityCall(
                            h.getName(),
                            h.getDesc(),
                            h.getTag() == Opcodes.H_INVOKESTATIC,
                            null);
                }
            }
        }

        @Override
        public void visitInsn(int opcode) {
            pending =
                    opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5
                            ? opcode - Opcodes.ICONST_0
                            : null;
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            pending = null;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            pending = null;
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            pending = null;
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            pending = null;
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            pending = null;
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            pending = null;
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            pending = null;
        }
    }
}
