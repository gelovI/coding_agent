package org.ivangelov.agent.orchestrator.tools

class DefaultToolNameResolver : ToolNameResolver {
    override fun resolve(llmName: String): String {
        val n = llmName.trim().lowercase()
        return when (n) {
            "list_dir",
            "repo_browser.list_dir",
            "repo_browser.listfiles",
            "tool.file.list" -> "list_dir"

            "read_file",
            "repo_browser.read_file",
            "repo_browser.readfile",
            "tool.file.read" -> "read_file"

            "write_file",
            "repo_browser.write_file",
            "repo_browser.writefile",
            "tool.file.write",
            "create_file" -> "write_file"

            "index_project",
            "indexproject",
            "project.index",
            "code.index" -> "index_project"

            "analyze_architecture",
            "analyzearchitecture",
            "project.analyze",
            "architecture.analyze" -> "analyze_architecture"

            else -> n
        }
    }
}