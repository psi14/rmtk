using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace InkBridge.Net;

/// <summary>
/// Ligação por cabo USB: usa o "adb" (Android platform-tools) para encaminhar
/// a porta 47800 do tablet para o PC ("adb reverse"). O tablet precisa da
/// "Depuração USB" ligada nas Opções de programador.
/// </summary>
public static class Adb
{
    private static readonly HashSet<string> Reversed = new();

    public static string? Find()
    {
        bool win = OperatingSystem.IsWindows();
        string exe = win ? "adb.exe" : "adb";
        var candidates = new List<string>
        {
            Path.Combine(AppContext.BaseDirectory, "platform-tools", exe),
            Path.Combine(AppContext.BaseDirectory, exe),
        };
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (!string.IsNullOrEmpty(local)) candidates.Add(Path.Combine(local, "Android", "Sdk", "platform-tools", exe));
        var sdk = Environment.GetEnvironmentVariable("ANDROID_HOME") ?? Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT");
        if (!string.IsNullOrEmpty(sdk)) candidates.Add(Path.Combine(sdk, "platform-tools", exe));
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
            if (!string.IsNullOrWhiteSpace(dir)) candidates.Add(Path.Combine(dir.Trim(), exe));
        return candidates.FirstOrDefault(File.Exists);
    }

    private static (int code, string output) Run(string adb, string args, int timeoutMs = 8000)
    {
        var psi = new ProcessStartInfo(adb, args)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        using var p = Process.Start(psi)!;
        var stdout = p.StandardOutput.ReadToEndAsync();
        var stderr = p.StandardError.ReadToEndAsync();
        if (!p.WaitForExit(timeoutMs)) { try { p.Kill(); } catch { } return (-1, "adb não respondeu"); }
        return (p.ExitCode, (stdout.Result + stderr.Result).Trim());
    }

    /// <summary>Tablets ligados por USB e autorizados.</summary>
    public static List<string> Devices(string adb)
    {
        var (_, output) = Run(adb, "devices");
        return output.Split('\n')
            .Select(l => l.Trim().Split('\t'))
            .Where(p => p.Length == 2 && p[1] == "device")
            .Select(p => p[0])
            .ToList();
    }

    /// <summary>Faz "adb reverse" para todos os tablets ligados. Devolve uma mensagem para o utilizador.</summary>
    public static Task<string> ReverseAllAsync(bool onlyNew) => Task.Run(() =>
    {
        var adb = Find();
        if (adb == null)
            return "adb não encontrado. Instale as «Android SDK Platform-Tools» e copie a pasta platform-tools para junto do InkBridge.exe.";
        try
        {
            var devices = Devices(adb);
            if (devices.Count == 0)
            {
                lock (Reversed) Reversed.Clear();
                return "Nenhum tablet por USB. Ligue o cabo e aceite «Permitir depuração USB» no tablet.";
            }
            var msgs = new List<string>();
            foreach (var d in devices)
            {
                lock (Reversed) if (onlyNew && Reversed.Contains(d)) continue;
                var (code, output) = Run(adb, $"-s {d} reverse tcp:{LinkServer.TcpPort} tcp:{LinkServer.TcpPort}");
                if (code == 0)
                {
                    lock (Reversed) Reversed.Add(d);
                    msgs.Add($"USB pronto ({d}). No tablet escolha «Cabo USB».");
                }
                else msgs.Add($"Falhou em {d}: {output}");
            }
            lock (Reversed) Reversed.RemoveWhere(r => !devices.Contains(r));
            return string.Join("\n", msgs);
        }
        catch (Exception e)
        {
            return "Erro ao usar o adb: " + e.Message;
        }
    });
}
