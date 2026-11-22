package app.jangkau

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.jangkau.core.Terminal
import app.jangkau.core.parseGitStatus
import app.jangkau.model.Hop
import app.jangkau.model.HostProfile
import app.jangkau.security.StoredKey
import app.jangkau.ui.ConnectScreen
import app.jangkau.ui.EditorTab
import app.jangkau.ui.HostKeyDialog
import app.jangkau.ui.HostKeyPrompt
import app.jangkau.ui.JangkauTheme
import app.jangkau.ui.SidePanel
import app.jangkau.ui.WorkspaceScreen
import app.jangkau.ui.WorkspaceState
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land-xhdpi", sdk = [34])
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val profile = HostProfile(
        name = "Lab Server", host = "dev.example.com", user = "user", keyId = "k1",
        jump = Hop("bastion.example.com", 22, "user"), workDir = "~/climate-model",
        pythonCmd = "conda run -n climate python",
    )

    private val trainPy = """
        |import argparse
        |from pathlib import Path
        |
        |import numpy as np
        |import torch
        |from torch.utils.data import DataLoader
        |
        |from model import RainNet
        |from data import load_era5
        |
        |
        |def train(epochs: int, lr: float = 3e-4, out: Path = Path("runs")) -> float:
        |    ds = load_era5("data/era5/2015-2024.zarr")
        |    loader = DataLoader(ds, batch_size=64, shuffle=True, num_workers=8)
        |    model = RainNet(channels=32).cuda()
        |    opt = torch.optim.AdamW(model.parameters(), lr=lr)
        |    best = np.inf
        |    for epoch in range(epochs):
        |        for x, y in loader:
        |            loss = torch.nn.functional.mse_loss(model(x.cuda()), y.cuda())
        |            opt.zero_grad()
        |            loss.backward()
        |            opt.step()
        |        best = min(best, loss.item())
        |        print(f"epoch {epoch:03d}  loss {loss.item():.4f}")
        |    torch.save(model.state_dict(), out / "rainnet.pt")
        |    return best
        |
        |
        |if __name__ == "__main__":
        |    p = argparse.ArgumentParser()
        |    p.add_argument("--epochs", type=int, default=20)
        |    print(train(p.parse_args().epochs))
        |""".trimMargin()

    private fun fakeServer() = FakeRemote().apply {
        val root = "/home/user/climate-model"
        dir("$root/data"); dir("$root/notebooks"); dir("$root/runs")
        file("$root/.gitignore", "runs/\n")
        file("$root/README.md", "# Climate model\n")
        file("$root/requirements.txt", "torch\nnumpy\n")
        file("$root/environment.yml", "name: climate\n")
        file("$root/src/model.py", "class RainNet: ...\n")
        file("$root/src/data.py", "def load_era5(p): ...\n")
        file("$root/src/train.py", trainPy)
        file("$root/jobs/train.slurm", "#!/bin/bash\n#SBATCH --gres=gpu:1\n")
        file("$root/jobs/sweep.sh", "for lr in 1e-3 3e-4; do sbatch train.slurm \$lr; done\n")
        file("$root/src/kernels.cu", "__global__ void conv() {}\n")
        file("$root/dashboard/index.html", "<!doctype html>\n")
        file("$root/dashboard/app.ts", "export const app = 1\n")
        file("$root/dashboard/style.css", "body {}\n")
        file("$root/Dockerfile", "FROM python:3.12\n")
        file("$root/Makefile", "all:\n")
        file("$root/pyproject.toml", "[project]\n")
        execHandler = { cmd ->
            when {
                "__codetab_lsp__" in cmd -> app.jangkau.core.ExecResult(0, "__codetab_lsp__ pyright-langserver\n", "")
                "while :; do for m in" in cmd -> app.jangkau.core.ExecResult(0, "$root\n", "")
                else -> app.jangkau.core.ExecResult(0, "", "")
            }
        }
        serverFactory = { _, _ ->
            FakeLsp(app.jangkau.core.LspServerInfo("Pyright", "1.1", listOf("."), listOf("("), true, true, true, true, true))
        }
    }

    private fun showProblems(ws: WorkspaceState) {
        val server = (ws.remote as FakeRemote).servers.lastOrNull() ?: return
        val lines = trainPy.lines()
        val line = lines.indexOfFirst { "best = min" in it }
        val col = lines[line].indexOf("loss")
        fun p(l: Int, c: Int) = app.jangkau.core.LspPosition(l.toUInt(), c.toUInt())
        server.listener!!.onDiagnostics(
            "/home/user/climate-model/src/train.py",
            listOf(app.jangkau.core.LspDiagnostic(app.jangkau.core.LspRange(p(line, col), p(line, col + 4)), 2u,
                "\"loss\" is possibly unbound", "Pyright", "reportPossiblyUnbound")),
        )
    }

    private fun workspace(scope: kotlinx.coroutines.CoroutineScope, panel: SidePanel): WorkspaceState {
        val server = fakeServer()
        val ws = WorkspaceState(server, profile, scope)
        val root = ws.root
        ws.preload(root, server.list(root))
        ws.preload("$root/src", server.list("$root/src"), open = true)
        ws.preload("$root/jobs", server.list("$root/jobs"), open = true)
        ws.preload("$root/dashboard", server.list("$root/dashboard"), open = true)
        ws.latencyMs = 38
        ws.git.repo = root
        ws.git.status = parseGitStatus("## main...origin/main [ahead 2]\u0000 M src/train.py\u0000M  jobs/train.slurm\u0000?? jobs/sweep.sh\u0000")
        ws.git.commitMessage = if (panel == SidePanel.Git) "Add learning rate sweep" else ""
        ws.panel = panel
        val tab = EditorTab("$root/src/train.py", "$root/src/train.py", "train.py", trainPy, 1)
        tab.dirty = true
        tab.cursorLine = 21; tab.cursorCol = 30
        ws.tabs += EditorTab("$root/README.md", "$root/README.md", "README.md", "# Climate model\n", 1)
        ws.tabs += tab
        ws.tabs += EditorTab("$root/jobs/train.slurm", "$root/jobs/train.slurm", "train.slurm", "#!/bin/bash\n", 1)
        ws.activeKey = tab.key
        ws.selectedPath = tab.path
        ws.attachLsp(tab)

        val term = Terminal(150u, 14u)
        val esc = "\u001b"
        term.feed((
            "$esc[1;32muser@dev$esc[0m:$esc[1;34m~/climate-model$esc[0m$ git pull --ff-only\r\n" +
            "Already up to date.\r\n" +
            "$esc[1;32muser@dev$esc[0m:$esc[1;34m~/climate-model$esc[0m$ nvidia-smi --query-gpu=name,memory.used --format=csv,noheader\r\n" +
            "NVIDIA A100-SXM4-80GB, 1024 MiB\r\n" +
            "$esc[1;32muser@dev$esc[0m:$esc[1;34m~/climate-model$esc[0m$ cd src && python train.py --epochs 3\r\n" +
            "epoch 000  loss 0.8412\r\nepoch 001  loss 0.5127\r\nepoch 002  loss $esc[33m0.3981$esc[0m\r\n" +
            "$esc[32m0.3981$esc[0m\r\n" +
            "$esc[1;32muser@dev$esc[0m:$esc[1;34m~/climate-model/src$esc[0m$ "
        ).toByteArray())
        ws.attachTerminal(term, "bash 1")
        ws.attachTerminal(Terminal(80u, 10u), "nvidia-smi")
        ws.activeTerminal = 0
        return ws
    }

    private fun settle() {
        repeat(6) { compose.waitForIdle(); Thread.sleep(400) }
        compose.waitForIdle()
    }

    @Test fun workspaceExplorer() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val ws = androidx.compose.runtime.remember { workspace(scope, SidePanel.Explorer) }
            androidx.compose.runtime.LaunchedEffect(ws.lsp.statusFor("python")) { showProblems(ws) }
            JangkauTheme { WorkspaceScreen(ws, onDisconnect = {}, onReconnect = {}) }
        }
        settle()
        compose.onRoot().captureRoboImage("build/shots/01_workspace_explorer.png")
    }

    @Test fun workspaceGit() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val ws = androidx.compose.runtime.remember { workspace(scope, SidePanel.Git) }
            JangkauTheme { WorkspaceScreen(ws, onDisconnect = {}, onReconnect = {}) }
        }
        settle()
        compose.onRoot().captureRoboImage("build/shots/02_workspace_git.png")
    }

    @Test fun imageViewer() {
        val bmp = android.graphics.Bitmap.createBitmap(960, 600, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val axis = android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; strokeWidth = 3f }
        c.drawLine(80f, 520f, 920f, 520f, axis); c.drawLine(80f, 520f, 80f, 60f, axis)
        val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(31, 119, 180); strokeWidth = 5f; style = android.graphics.Paint.Style.STROKE
        }
        val path = android.graphics.Path().apply {
            moveTo(80f, 120f)
            for (i in 1..40) { val x = 80f + i * 21f; lineTo(x, 520f - 400f * kotlin.math.exp(-i / 9f).toFloat() - 30f * kotlin.math.sin(i / 2f)) }
        }
        c.drawPath(path, line)
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "codetab-shot").apply { mkdirs() }
        val f = java.io.File(dir, "loss.png").also { out -> out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val ws = androidx.compose.runtime.remember {
                workspace(scope, SidePanel.Explorer).also { w ->
                    val root = w.root
                    w.preload("$root/runs", listOf(
                        app.jangkau.core.RemoteEntry("$root/runs/loss.png", "loss.png", false, false, f.length().toULong(), 0u),
                        app.jangkau.core.RemoteEntry("$root/runs/report.pdf", "report.pdf", false, false, 120_000u, 0u),
                    ), open = true)
                    val t = EditorTab("$root/runs/loss.png", "$root/runs/loss.png", "loss.png", "", 1, readOnly = true,
                        language = "Image", viewer = app.jangkau.editor.Languages.Viewer.Image, file = f, size = f.length())
                    w.tabs += t
                    w.activeKey = t.key
                    w.terminalVisible = false
                }
            }
            JangkauTheme { WorkspaceScreen(ws, onDisconnect = {}, onReconnect = {}) }
        }
        settle()
        compose.onRoot().captureRoboImage("build/shots/05_image_viewer.png")
    }

    @Test fun connectScreen() {
        val keys = listOf(
            StoredKey("k1", "tablet", "ed25519", "ssh-ed25519 AAAAC3Nza... tablet@codetab", "SHA256:q7Xh1y0kq2c9PZ0r3y1v8m1Wf0bS6yA2nJ4dC5eR8tU", false, 1),
            StoredKey("k2", "old laptop", "OpenSSH", null, "SHA256:Zt1o0b9Vq3m5L8xP2kR7wN4cJ6hF1aE9sD3gB5uY7iQ", true, 2),
        )
        val profiles = listOf(
            profile,
            HostProfile(name = "GPU Workstation", host = "gpu-box.local", port = 2222, user = "user", keyId = "k2", workDir = "~/work", pythonCmd = "python3"),
        )
        compose.setContent {
            JangkauTheme {
                ConnectScreen(profiles, keys, {}, {}, {}, { keys[0] }, { _, _ -> keys[0] }, {})
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/shots/03_connect.png")
    }

    @Test fun hostKeyPrompt() {
        compose.setContent {
            JangkauTheme {
                Box(Modifier.fillMaxSize()) {
                    HostKeyDialog(
                        HostKeyPrompt("bastion.example.com", 22, "ssh-ed25519 SHA256:dqALNePmKw3ZqyOPT3EmVlvRWYP3sL7faNPwgq4KK0U", null, CompletableDeferred()),
                        onAnswer = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/shots/04_host_key.png")
    }
}
