package com.agenticai.core.sdlc.harness;

/**
 * The isolation boundary the harness runs in: one workspace directory, two shell handles.
 *
 * <ul>
 *   <li>{@link #internal()} - for the harness's own fixed-template commands (git checkpoints, file
 *       reads and writes, the repository clone). Nothing user- or model-authored is ever passed to
 *       it as a command; paths and contents go through {@link WorkspaceFiles}, which validates them.</li>
 *   <li>{@link #restricted()} - for the test command. It refuses anything that is not an
 *       allowlisted runner invocation ({@link CodingCommandPolicy}), scrubs the environment and,
 *       unless network access was explicitly enabled, cuts the network.</li>
 * </ul>
 *
 * <p>The production implementation is {@link E2bCodingSandbox}. {@link LocalCodingSandbox} runs on
 * the host and exists for development only (see {@code app.coding-harness.allow-local-runner}).
 */
public interface CodingSandbox extends AutoCloseable {

    ShellRunner internal();

    ShellRunner restricted();

    /** Unpacks an archive already passed through {@link RepoArchive#sanitize} into the workspace. */
    void seedFromArchive(byte[] sanitizedZip);

    /** Clones a URL already passed through {@link GitUrlPolicy#check} into the workspace. */
    void seedFromGit(String httpsUrl);

    /** Initialises git in the seeded workspace and keeps build output out of the checkpoints. */
    void prepareRepository();

    /** Short description for logs ("e2b sandbox abc123"). */
    String describe();

    /** Releases the sandbox. Idempotent, never throws. */
    @Override
    void close();
}
