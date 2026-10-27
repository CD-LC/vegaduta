#!/bin/bash -eu
# ClusterFuzzLite build for the two Jazzer targets:
#   com.agenticai.core.chat.fuzz.RedactionFuzzTarget         (module chat)
#   com.agenticai.core.agents.skills.learning.fuzz.ScrubberFuzzTarget (module agents-jobs)
# Produces $OUT/{chat.jar,agents-jobs.jar,deps/*.jar,RedactionFuzzer,ScrubberFuzzer}
# - the canonical OSS-Fuzz/CFL jvm layout (jazzer_driver + jazzer_agent_deploy.jar
#   are staged by the runner image next to the artifacts).

cd "$SRC/vegaduta/core"

# 1. Build the reactor slice the targets depend on, install so the classpath
#    plugin can resolve the snapshot artifacts, compile the test classes.
mvn -B -ntp -DskipTests -pl chat,agents-jobs -am install
mvn -B -ntp -pl chat,agents-jobs test-compile

# 2. Resolve each module's dependency classpath into its own file.
mvn -B -ntp -pl chat dependency:build-classpath \
    -Dmdep.outputFile="$OUT/cp-chat.txt" -Dmdep.includeScope=test
mvn -B -ntp -pl agents-jobs dependency:build-classpath \
    -Dmdep.outputFile="$OUT/cp-jobs.txt" -Dmdep.includeScope=test

# 3. Jar the module classes + test classes (the targets live in test sources).
jar cf "$OUT/chat.jar" -C chat/target/classes . -C chat/target/test-classes .
jar cf "$OUT/agents-jobs.jar" -C agents-jobs/target/classes . -C agents-jobs/target/test-classes .

# 4. Stage every dependency jar beside the fuzzers.
mkdir -p "$OUT/deps"
cat "$OUT"/cp-*.txt | tr ':' '\n' | sort -u | while read -r j; do
    [ -f "$j" ] && cp "$j" "$OUT/deps/"
done

# 5. Bundle a JRE 21. The run_fuzzers container (base-runner-jvm) only ships
#    JDK 17, which cannot load our release-21 classes
#    (UnsupportedClassVersionError 65 vs 61). jazzer_driver launches
#    "$JAVA_HOME/bin/java", so a jlink runtime in $OUT + JAVA_HOME override
#    in each launcher makes the fuzzers run on the same JDK they were
#    compiled with. jlink keeps it to the modules actually needed (~90MB
#    compressed instead of a full 300MB JDK).
"$JAVA_HOME/bin/jlink" \
    --add-modules java.se,jdk.unsupported,jdk.jfr,jdk.zipfs \
    --compress=2 --no-man-pages --no-header-files \
    --output "$OUT/jdk21"

# 6. One executable launcher per target - the canonical CFL jvm wrapper.
make_launcher() {
    local name="$1" fqcn="$2"
    cat > "$OUT/$name" <<EOF
#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
if [[ "\$@" =~ (^| )-runs=[0-9]+($| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
export JAVA_HOME="\$this_dir/jdk21"
export PATH="\$JAVA_HOME/bin:\$PATH"
# jazzer_driver is a native binary: it dlopens libjvm.so from
# JVM_LD_LIBRARY_PATH, which the runner pre-sets to its JDK 17 - repoint it
# at our bundled JRE or the v65 classes still fail UnsupportedClassVersionError.
export JVM_LD_LIBRARY_PATH="\$this_dir/jdk21/lib/server"
LD_LIBRARY_PATH="\$this_dir/jdk21/lib/server:\$this_dir" \\
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \\
--cp=\$this_dir/chat.jar:\$this_dir/agents-jobs.jar:\$this_dir/deps/* \\
--target_class=$fqcn \\
--jvm_args="\$mem_settings:-Djava.awt.headless=true" \\
\$@
EOF
    chmod +x "$OUT/$name"
}

make_launcher RedactionFuzzer com.agenticai.core.chat.fuzz.RedactionFuzzTarget
make_launcher ScrubberFuzzer com.agenticai.core.agents.skills.learning.fuzz.ScrubberFuzzTarget
