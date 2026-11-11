using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.Json;

namespace InkBridge.Model;

public enum StrokeKind { Pen, Highlighter }

/// <summary>Mesmas constantes do tablet (Model.kt → TemplateSpec).</summary>
public static class TemplateSpec
{
    public const double LineSpacing = 36;
    public const double LineTop = 120;
    public const double MarginX = 90;
    public const double GridSpacing = 30;
    public const double DotSpacing = 30;
    public const double DotRadius = 1.6;
    public const double LineWidth = 1;
    public const uint LineColor = 0xFFC9D3E6;
    public const uint MarginColor = 0xFFF2B8B8;
    public const byte HighlighterAlpha = 0x60;
}

/// <summary>Um traço: pontos (x, y, pressão) em unidades da página.</summary>
public sealed class Stroke
{
    public string Id { get; }
    public StrokeKind Kind { get; }
    public uint Color { get; }
    public double Width { get; }
    public List<float> Pts { get; }

    /// <summary>Muda sempre que entram pontos novos (para refazer a geometria em cache).</summary>
    public int Version { get; private set; }
    public object? Cache { get; set; }
    public int CacheVersion { get; set; } = -1;

    public Stroke(string id, StrokeKind kind, uint color, double width, List<float>? pts = null)
    {
        Id = id; Kind = kind; Color = color; Width = width; Pts = pts ?? new List<float>();
    }

    public int Count => Pts.Count / 3;
    public float X(int i) => Pts[i * 3];
    public float Y(int i) => Pts[i * 3 + 1];
    public float P(int i) => Pts[i * 3 + 2];

    public void Append(IEnumerable<float> pts)
    {
        Pts.AddRange(pts);
        Version++;
    }

    public double WidthAt(int i) => WidthFor(Kind, Width, P(i));

    public static double WidthFor(StrokeKind kind, double baseW, double pressure) =>
        kind == StrokeKind.Highlighter ? baseW : baseW * (0.35 + 0.65 * Math.Clamp(pressure, 0, 1));
}

public sealed class Page
{
    public string Id { get; set; } = "";
    public string Template { get; set; } = "lined";
    public double W { get; set; } = 1000;
    public double H { get; set; } = 1414;
    public List<Stroke> Strokes { get; } = new();
}

public sealed class Notebook
{
    public string Id { get; set; } = "";
    public string Title { get; set; } = "Caderno";
    public int Current { get; set; }
    public long Modified { get; set; }
    public List<Page> Pages { get; } = new();

    public Page? PageById(string id) => Pages.FirstOrDefault(p => p.Id == id);
}

/// <summary>Leitura/escrita no mesmo formato JSON do tablet.</summary>
public static class NotebookJson
{
    public static StrokeKind ParseKind(string? k) => k == "hl" ? StrokeKind.Highlighter : StrokeKind.Pen;

    /// <summary>A cor vem como inteiro com sinal (ARGB do Android).</summary>
    public static uint ParseColor(JsonElement e) =>
        e.ValueKind == JsonValueKind.Number ? unchecked((uint)e.GetInt64()) : 0xFF000000;

    public static List<float> ParsePoints(JsonElement arr)
    {
        var list = new List<float>(arr.GetArrayLength());
        foreach (var v in arr.EnumerateArray()) list.Add(v.GetSingle());
        int extra = list.Count % 3;
        if (extra != 0) list.RemoveRange(list.Count - extra, extra);
        return list;
    }

    public static Stroke ParseStroke(JsonElement o) => new(
        o.GetProperty("id").GetString() ?? "",
        ParseKind(o.TryGetProperty("k", out var k) ? k.GetString() : null),
        o.TryGetProperty("c", out var c) ? ParseColor(c) : 0xFF000000,
        o.TryGetProperty("w", out var w) ? w.GetDouble() : 3,
        o.TryGetProperty("pts", out var p) ? ParsePoints(p) : null);

    public static Page ParsePage(JsonElement o)
    {
        var page = new Page
        {
            Id = o.GetProperty("id").GetString() ?? "",
            Template = o.TryGetProperty("tpl", out var t) ? t.GetString() ?? "blank" : "blank",
            W = o.TryGetProperty("w", out var w) ? w.GetDouble() : 1000,
            H = o.TryGetProperty("h", out var h) ? h.GetDouble() : 1414,
        };
        if (o.TryGetProperty("strokes", out var arr))
            foreach (var s in arr.EnumerateArray()) page.Strokes.Add(ParseStroke(s));
        return page;
    }

    public static Notebook ParseNotebook(JsonElement o)
    {
        var nb = new Notebook
        {
            Id = o.GetProperty("id").GetString() ?? "",
            Title = o.TryGetProperty("title", out var t) ? t.GetString() ?? "Caderno" : "Caderno",
            Current = o.TryGetProperty("cur", out var c) ? c.GetInt32() : 0,
            Modified = o.TryGetProperty("modified", out var m) ? m.GetInt64() : 0,
        };
        foreach (var p in o.GetProperty("pages").EnumerateArray()) nb.Pages.Add(ParsePage(p));
        if (nb.Pages.Count == 0) nb.Pages.Add(new Page { Id = Guid.NewGuid().ToString("N")[..12] });
        nb.Current = Math.Clamp(nb.Current, 0, nb.Pages.Count - 1);
        return nb;
    }

    // ---------------------------------------------------------------- escrita

    private static void Num(StringBuilder sb, double v, int decimals = 2) =>
        sb.Append(Math.Round(v, decimals).ToString("0.###", CultureInfo.InvariantCulture));

    private static void Str(StringBuilder sb, string s) => sb.Append(JsonSerializer.Serialize(s));

    public static string Serialize(Notebook nb)
    {
        var sb = new StringBuilder(4096);
        sb.Append("{\"id\":"); Str(sb, nb.Id);
        sb.Append(",\"title\":"); Str(sb, nb.Title);
        sb.Append(",\"cur\":").Append(nb.Current);
        sb.Append(",\"modified\":").Append(nb.Modified);
        sb.Append(",\"pages\":[");
        for (int i = 0; i < nb.Pages.Count; i++)
        {
            var p = nb.Pages[i];
            if (i > 0) sb.Append(',');
            sb.Append("{\"id\":"); Str(sb, p.Id);
            sb.Append(",\"tpl\":"); Str(sb, p.Template);
            sb.Append(",\"w\":"); Num(sb, p.W);
            sb.Append(",\"h\":"); Num(sb, p.H);
            sb.Append(",\"strokes\":[");
            for (int j = 0; j < p.Strokes.Count; j++)
            {
                var s = p.Strokes[j];
                if (j > 0) sb.Append(',');
                sb.Append("{\"id\":"); Str(sb, s.Id);
                sb.Append(",\"k\":").Append(s.Kind == StrokeKind.Highlighter ? "\"hl\"" : "\"pen\"");
                sb.Append(",\"c\":").Append(unchecked((int)s.Color));
                sb.Append(",\"w\":"); Num(sb, s.Width);
                sb.Append(",\"pts\":[");
                for (int k = 0; k < s.Pts.Count; k++)
                {
                    if (k > 0) sb.Append(',');
                    Num(sb, s.Pts[k], k % 3 == 2 ? 3 : 2);
                }
                sb.Append("]}");
            }
            sb.Append("]}");
        }
        sb.Append("]}");
        return sb.ToString();
    }
}
