package com.github.stuartwdouglas.log4jjndi;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class TransformationSupport {

    public static void run(Instrumentation inst) {

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
                if (className == null) {
                    return classfileBuffer;
                }
                if (!className.equals("org/apache/logging/log4j/core/lookup/JndiLookup")) {
                    return classfileBuffer;
                }

                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter cw = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

                ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
                        return new MethodVisitor(Opcodes.ASM9, null) {

                            @Override
                            public void visitCode() {
                                target.visitCode();
                                target.visitInsn(Opcodes.ACONST_NULL);
                                target.visitInsn(Opcodes.ARETURN);
                                target.visitEnd();
                            }
                        };
                    }
                };
                reader.accept(cv, ClassReader.EXPAND_FRAMES);

                return cw.toByteArray();

            }

        });

    }
}
