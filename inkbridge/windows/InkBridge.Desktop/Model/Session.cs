using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;

namespace InkBridge.Model;

/// <summary>
/// Estado do caderno recebido do tablet. As mensagens são lidas numa thread de fundo
/// (<see cref="Prepare"/>) e aplicadas na thread da interface.
/// </summary>
public sealed class Session
{
    public Notebook? Notebook { get; private set; }
    public string? Device { get; set; }

    /// <summary>Posição da caneta a pairar sobre a página atual do tablet (unidades da página).</summary>
    public (double x, double y)? Hover { get; private set; }

    /// <summary>Muda páginas, título ou caderno.</summary>
    public event Action? StructureChanged;
    /// <summary>Traços de uma página mudaram.</summary>
    public event Action<Page>? ContentChanged;
    public event Action? HoverChanged;

    public bool Dirty { get; set; }

    private readonly Dictionary<string, (Page page, Stroke stroke)> _active = new();

    /// <summary>Lê a linha (pode ser numa thread de fundo) e devolve o que aplicar na interface.</summary>
    public Action? Prepare(string line)
    {
        JsonElement root;
        try
        {
            using var doc = JsonDocument.Parse(line);
            root = doc.RootElement.Clone();
        }
        catch (JsonException) { return null; }

        var t = root.TryGetProperty("t", out var te) ? te.GetString() : null;
        switch (t)
        {
            case "doc":
                var nb = NotebookJson.ParseNotebook(root.GetProperty("nb"));
                return () => SetNotebook(nb);
            case "add":
                var added = root.GetProperty("strokes").EnumerateArray().Select(NotebookJson.ParseStroke).ToList();
                var pg = root.GetProperty("pg").GetString() ?? "";
                return () => AddStrokes(pg, added);
            default:
                return () => Apply(t, root);
        }
    }

    public void SetNotebook(Notebook nb)
    {
        Notebook = nb;
        _active.Clear();
        Dirty = true;
        StructureChanged?.Invoke();
    }

    private void AddStrokes(string pageId, List<Stroke> strokes)
    {
        var page = Notebook?.PageById(pageId);
        if (page == null) return;
        var ids = strokes.Select(s => s.Id).ToHashSet();
        page.Strokes.RemoveAll(s => ids.Contains(s.Id));
        page.Strokes.AddRange(strokes);
        Dirty = true;
        ContentChanged?.Invoke(page);
    }

    private void Apply(string? t, JsonElement o)
    {
        var nb = Notebook;
        switch (t)
        {
            case "cur":
                if (nb == null) return;
                nb.Current = Math.Clamp(o.GetProperty("i").GetInt32(), 0, nb.Pages.Count - 1);
                Hover = null;
                StructureChanged?.Invoke();
                break;

            case "sb":
            {
                var page = nb?.PageById(o.GetProperty("pg").GetString() ?? "");
                if (page == null) return;
                var s = new Stroke(
                    o.GetProperty("id").GetString() ?? "",
                    NotebookJson.ParseKind(o.TryGetProperty("k", out var k) ? k.GetString() : null),
                    o.TryGetProperty("c", out var c) ? NotebookJson.ParseColor(c) : 0xFF000000,
                    o.TryGetProperty("w", out var w) ? w.GetDouble() : 3);
                page.Strokes.Add(s);
                _active[s.Id] = (page, s);
                Hover = null;
                ContentChanged?.Invoke(page);
                break;
            }
            case "sp":
            {
                if (!_active.TryGetValue(o.GetProperty("id").GetString() ?? "", out var a)) return;
                a.stroke.Append(NotebookJson.ParsePoints(o.GetProperty("pts")));
                ContentChanged?.Invoke(a.page);
                break;
            }
            case "se":
            {
                var id = o.GetProperty("id").GetString() ?? "";
                if (_active.Remove(id, out var a))
                {
                    Dirty = true;
                    ContentChanged?.Invoke(a.page);
                }
                break;
            }
            case "sc":
            {
                var id = o.GetProperty("id").GetString() ?? "";
                if (_active.Remove(id, out var a))
                {
                    a.page.Strokes.Remove(a.stroke);
                    ContentChanged?.Invoke(a.page);
                }
                break;
            }
            case "del":
            {
                var page = nb?.PageById(o.GetProperty("pg").GetString() ?? "");
                if (page == null) return;
                var ids = o.GetProperty("ids").EnumerateArray().Select(e => e.GetString()).ToHashSet();
                page.Strokes.RemoveAll(s => ids.Contains(s.Id));
                Dirty = true;
                ContentChanged?.Invoke(page);
                break;
            }
            case "hv":
            {
                bool on = o.TryGetProperty("on", out var onE) && onE.GetInt32() == 1;
                Hover = on ? (o.GetProperty("x").GetDouble(), o.GetProperty("y").GetDouble()) : null;
                HoverChanged?.Invoke();
                break;
            }
        }
    }

    /// <summary>Tablet desligou: traços a meio ficam como estão.</summary>
    public void Disconnected()
    {
        _active.Clear();
        Hover = null;
        HoverChanged?.Invoke();
    }
}
