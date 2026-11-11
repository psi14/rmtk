using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace InkBridge.Net;

/// <summary>
/// Servidor no PC. O tablet liga-se por TCP (porta 47800); o PC anuncia-se por
/// UDP broadcast (porta 47801) para o tablet o encontrar sozinho na rede Wi-Fi.
/// Por USB, "adb reverse" encaminha a porta 47800 do tablet para este servidor.
/// </summary>
public sealed class LinkServer : IDisposable
{
    public const int TcpPort = 47800;
    public const int UdpPort = 47801;

    /// <summary>Uma linha recebida (chamado numa thread de fundo).</summary>
    public event Action<string>? LineReceived;
    /// <summary>Tablet ligou (nome, endereço) ou desligou (null, null).</summary>
    public event Action<string?, string?>? ClientChanged;

    public string PcName { get; } = Environment.MachineName;
    public string? Error { get; private set; }

    private readonly CancellationTokenSource _cts = new();
    private TcpListener? _listener;
    private TcpClient? _client;
    private StreamWriter? _writer;
    private readonly object _writeLock = new();

    public void Start()
    {
        try
        {
            _listener = new TcpListener(IPAddress.Any, TcpPort);
            _listener.Start();
            _ = Task.Run(AcceptLoop);
        }
        catch (Exception e)
        {
            Error = $"Não foi possível abrir a porta {TcpPort}: {e.Message}";
        }
        _ = Task.Run(BeaconLoop);
    }

    private async Task AcceptLoop()
    {
        while (!_cts.IsCancellationRequested)
        {
            TcpClient c;
            try { c = await _listener!.AcceptTcpClientAsync(_cts.Token); }
            catch { break; }
            c.NoDelay = true;
            c.ReceiveTimeout = 9000;
            // só um tablet de cada vez: o mais recente substitui o anterior
            var old = _client;
            _client = c;
            try { old?.Close(); } catch { }
            _ = Task.Run(() => HandleClient(c));
        }
    }

    private void HandleClient(TcpClient c)
    {
        var endpoint = (c.Client.RemoteEndPoint as IPEndPoint)?.Address.ToString() ?? "?";
        try
        {
            var stream = c.GetStream();
            var reader = new StreamReader(stream, new UTF8Encoding(false), false, 64 * 1024);
            var writer = new StreamWriter(stream, new UTF8Encoding(false)) { AutoFlush = true, NewLine = "\n" };
            lock (_writeLock) { if (_client == c) _writer = writer; }
            Send("{\"t\":\"welcome\",\"name\":" + JsonSerializer.Serialize(PcName) + "}");

            string? device = null;
            while (!_cts.IsCancellationRequested)
            {
                var line = reader.ReadLine();
                if (line == null) break;
                if (_client != c) break;
                var type = PeekType(line);
                if (type == "ping")
                {
                    Send("{\"t\":\"pong\"}");
                    continue;
                }
                if (type == "hello")
                {
                    try
                    {
                        using var doc = JsonDocument.Parse(line);
                        device = doc.RootElement.GetProperty("device").GetString() ?? "Tablet";
                    }
                    catch { device = "Tablet"; }
                    ClientChanged?.Invoke(device, endpoint);
                    continue;
                }
                LineReceived?.Invoke(line);
            }
        }
        catch (Exception) { /* ligação caiu */ }
        finally
        {
            try { c.Close(); } catch { }
            if (_client == c)
            {
                _client = null;
                lock (_writeLock) _writer = null;
                ClientChanged?.Invoke(null, null);
            }
        }
    }

    /// <summary>Lê só o campo "t" (tipo da mensagem), sem analisar o resto.</summary>
    public static string? PeekType(string line)
    {
        try
        {
            var reader = new Utf8JsonReader(Encoding.UTF8.GetBytes(line.Length > 256 ? line[..256] : line),
                isFinalBlock: false, state: default);
            if (!reader.Read() || reader.TokenType != JsonTokenType.StartObject) return null;
            while (reader.Read())
            {
                if (reader.TokenType != JsonTokenType.PropertyName) return null;
                bool isType = reader.ValueTextEquals("t");
                if (!reader.Read()) return null;
                if (isType) return reader.TokenType == JsonTokenType.String ? reader.GetString() : null;
                if (reader.TokenType is JsonTokenType.StartObject or JsonTokenType.StartArray && !reader.TrySkip()) return null;
            }
        }
        catch (Exception) { }
        return null;
    }

    public void Send(string line)
    {
        lock (_writeLock)
        {
            try { _writer?.WriteLine(line); } catch { }
        }
    }

    // ---------------------------------------------------------------- anúncio na rede

    private async Task BeaconLoop()
    {
        using var udp = new UdpClient(AddressFamily.InterNetwork) { EnableBroadcast = true };
        var payload = Encoding.UTF8.GetBytes(
            "{\"t\":\"beacon\",\"name\":" + JsonSerializer.Serialize(PcName) + ",\"port\":" + TcpPort + ",\"v\":1}");
        while (!_cts.IsCancellationRequested)
        {
            foreach (var target in BroadcastAddresses())
            {
                try { await udp.SendAsync(payload, payload.Length, new IPEndPoint(target, UdpPort)); }
                catch { /* interface sem rede: ignora */ }
            }
            try { await Task.Delay(1000, _cts.Token); } catch { break; }
        }
    }

    private static IEnumerable<IPAddress> BroadcastAddresses()
    {
        var set = new HashSet<IPAddress> { IPAddress.Broadcast };
        foreach (var (addr, mask) in Ipv4Interfaces())
        {
            var a = addr.GetAddressBytes();
            var m = mask.GetAddressBytes();
            var b = new byte[4];
            for (int i = 0; i < 4; i++) b[i] = (byte)(a[i] | ~m[i]);
            set.Add(new IPAddress(b));
        }
        return set;
    }

    private static IEnumerable<(IPAddress addr, IPAddress mask)> Ipv4Interfaces()
    {
        NetworkInterface[] nics;
        try { nics = NetworkInterface.GetAllNetworkInterfaces(); }
        catch { yield break; }
        foreach (var ni in nics)
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            IPInterfaceProperties props;
            try { props = ni.GetIPProperties(); } catch { continue; }
            foreach (var u in props.UnicastAddresses)
            {
                if (u.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                if (u.IPv4Mask == null || u.IPv4Mask.Equals(IPAddress.Any)) continue;
                yield return (u.Address, u.IPv4Mask);
            }
        }
    }

    /// <summary>Endereços IPv4 deste PC (para mostrar ao utilizador).</summary>
    public static List<string> LocalAddresses() =>
        Ipv4Interfaces().Select(t => t.addr.ToString()).Where(a => !a.StartsWith("169.254.")).Distinct().ToList();

    public void Dispose()
    {
        _cts.Cancel();
        try { _listener?.Stop(); } catch { }
        try { _client?.Close(); } catch { }
    }
}
