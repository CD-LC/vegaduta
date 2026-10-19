package com.agenticai.core.agents.tools.localshell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShellCommandPolicyTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ls -la",
                "ls ~/Documents | head -20",
                "cat notes.txt | grep -i 'todo item' | sort | wc -l",
                "git status",
                "git log --oneline -n 5",
                "git diff HEAD~1",
                "find . -name '*.md' -maxdepth 2",
                "grep -rn \"needle\" src",
                "df -h",
                "ps aux | grep java",
                "jq '.items | length' data.json",
                "date",
                "echo 'a > b'"
            })
    void readOnlyCommandsAreRecognised(String command) {
        assertThat(ShellCommandPolicy.notReadOnly(command)).as(command).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ls > out.txt",
                "cat a >> b",
                "cat < a",
                "ls; rm -rf x",
                "ls && rm x",
                "ls & ",
                "echo $(whoami)",
                "echo `whoami`",
                "echo \"$HOME\"",
                "rm file",
                "curl https://example.com",
                "python3 -c 'print(1)'",
                "sh -c ls",
                "find . -name x -delete",
                "find . -exec cat {} +",
                "find . -fprint out.txt",
                "sort -o out.txt in.txt",
                "git push",
                "git commit -m x",
                "git branch -D old",
                "git -c core.pager=sh log",
                "git diff --output=patch.txt",
                "ls | ",
                "| ls",
                "ls 'unterminated",
                "ls\nrm x",
                "sed -i s/a/b/ f",
                "awk 'BEGIN{system(\"id\")}'",
                ""
            })
    void everythingElseIsNotReadOnly(String command) {
        assertThat(ShellCommandPolicy.notReadOnly(command)).as(command).isPresent();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sudo ls",
                "ls; sudo rm x",
                "su - root",
                "shutdown -h now",
                "reboot",
                ":(){ :|:& };:",
                "mkfs.ext4 /dev/sda1",
                "dd if=/dev/zero of=/dev/sda",
                "rm -rf /",
                "rm -rf ~",
                "rm -rf $HOME",
                "echo ok && rm -fr /*",
                "launchctl unload x"
            })
    void refusedForEveryCaller(String command) {
        assertThat(ShellCommandPolicy.refusal(command)).as(command).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ls -la", "rm -rf build", "rm notes.txt", "make test", "python3 script.py", "echo summary > out.md", "git push origin main"})
    void ordinaryCommandsAreNotRefused(String command) {
        assertThat(ShellCommandPolicy.refusal(command)).as(command).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void blankIsRefused() {
        assertThat(ShellCommandPolicy.refusal("  ")).isPresent();
        assertThat(ShellCommandPolicy.refusal(null)).isPresent();
    }
}
