using System;
using System.Collections.Generic;
using Avalonia;
using Avalonia.Media;
using InkBridge.Model;

namespace InkBridge.Views;

/// <summary>Desenha uma página (fundo + traços) em unidades da página.</summary>
public static class PageRenderer
{
    private static readonly Dictionary<(string, double, double), Geometry> TemplateCache = new();
    private static readonly IBrush White = Brushes.White;

    private static Color ToColor(uint argb) =>
        Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb);

    public static void DrawPage(DrawingContext ctx, Page page)
    {
        var rect = new Rect(0, 0, page.W, page.H);
        ctx.DrawRectangle(White, null, rect);
        DrawTemplate(ctx, page);
        using (ctx.PushClip(rect))
        {
            foreach (var s in page.Strokes) DrawStroke(ctx, s);
        }
    }

    private static void DrawTemplate(DrawingContext ctx, Page page)
    {
        if (page.Template == "blank") return;
        var key = (page.Template, page.W, page.H);
        if (!TemplateCache.TryGetValue(key, out var geo))
        {
            geo = BuildTemplate(page);
            TemplateCache[key] = geo;
        }
        var line = new SolidColorBrush(ToColor(TemplateSpec.LineColor));
        if (page.Template == "dots") ctx.DrawGeometry(line, null, geo);
        else ctx.DrawGeometry(null, new Pen(line, TemplateSpec.LineWidth), geo);
        if (page.Template == "lined")
        {
            var margin = new Pen(new SolidColorBrush(ToColor(TemplateSpec.MarginColor)), TemplateSpec.LineWidth);
            ctx.DrawLine(margin, new Point(TemplateSpec.MarginX, 0), new Point(TemplateSpec.MarginX, page.H));
        }
    }

    private static Geometry BuildTemplate(Page page)
    {
        var g = new StreamGeometry();
        using var c = g.Open();
        switch (page.Template)
        {
            case "lined":
                for (double y = TemplateSpec.LineTop; y < page.H; y += TemplateSpec.LineSpacing)
                {
                    c.BeginFigure(new Point(0, y), false); c.LineTo(new Point(page.W, y)); c.EndFigure(false);
                }
                break;
            case "grid":
                for (double x = TemplateSpec.GridSpacing; x < page.W; x += TemplateSpec.GridSpacing)
                {
                    c.BeginFigure(new Point(x, 0), false); c.LineTo(new Point(x, page.H)); c.EndFigure(false);
                }
                for (double y = TemplateSpec.GridSpacing; y < page.H; y += TemplateSpec.GridSpacing)
                {
                    c.BeginFigure(new Point(0, y), false); c.LineTo(new Point(page.W, y)); c.EndFigure(false);
                }
                break;
            case "dots":
                double r = TemplateSpec.DotRadius;
                for (double y = TemplateSpec.DotSpacing; y < page.H; y += TemplateSpec.DotSpacing)
                for (double x = TemplateSpec.DotSpacing; x < page.W; x += TemplateSpec.DotSpacing)
                {
                    c.BeginFigure(new Point(x + r, y), true);
                    c.ArcTo(new Point(x - r, y), new Size(r, r), 0, false, SweepDirection.Clockwise);
                    c.ArcTo(new Point(x + r, y), new Size(r, r), 0, false, SweepDirection.Clockwise);
                    c.EndFigure(true);
                }
                break;
        }
        return g;
    }

    private sealed class StrokeCache
    {
        public Geometry Geometry = null!;
        public IBrush? Brush;
        public IPen? Pen;
    }

    public static void DrawStroke(DrawingContext ctx, Stroke s)
    {
        if (s.Count == 0) return;
        if (s.Cache is not StrokeCache cache || s.CacheVersion != s.Version)
        {
            cache = Build(s);
            s.Cache = cache;
            s.CacheVersion = s.Version;
        }
        ctx.DrawGeometry(cache.Brush, cache.Pen, cache.Geometry);
    }

    private static StrokeCache Build(Stroke s)
    {
        var color = ToColor(s.Color);
        if (s.Kind == StrokeKind.Highlighter)
        {
            var hl = Color.FromArgb(TemplateSpec.HighlighterAlpha, color.R, color.G, color.B);
            var g = new StreamGeometry();
            using (var c = g.Open())
            {
                c.BeginFigure(new Point(s.X(0), s.Y(0)), false);
                if (s.Count == 1) c.LineTo(new Point(s.X(0) + 0.1, s.Y(0)));
                for (int i = 1; i < s.Count; i++) c.LineTo(new Point(s.X(i), s.Y(i)));
                c.EndFigure(false);
            }
            return new StrokeCache
            {
                Geometry = g,
                Pen = new Pen(new SolidColorBrush(hl), s.Width, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round),
            };
        }
        return new StrokeCache { Geometry = BuildOutline(s), Brush = new SolidColorBrush(color) };
    }

    /// <summary>
    /// Contorno de um traço de largura variável: lado esquerdo, tampa redonda no fim,
    /// lado direito ao contrário, tampa redonda no início. Preenchido de uma vez só (rápido).
    /// </summary>
    public static Geometry BuildOutline(Stroke s)
    {
        int n = s.Count;
        var g = new StreamGeometry();
        using var c = g.Open();
        c.SetFillRule(FillRule.NonZero);
        if (n == 1)
        {
            double r = Math.Max(s.WidthAt(0) / 2, 0.3);
            double x = s.X(0), y = s.Y(0);
            c.BeginFigure(new Point(x + r, y), true);
            c.ArcTo(new Point(x - r, y), new Size(r, r), 0, false, SweepDirection.Clockwise);
            c.ArcTo(new Point(x + r, y), new Size(r, r), 0, false, SweepDirection.Clockwise);
            c.EndFigure(true);
            return g;
        }

        var left = new Point[n];
        var right = new Point[n];
        double lastNx = 0, lastNy = 1;
        double dxEnd = 1, dyEnd = 0, dxStart = 1, dyStart = 0;
        for (int i = 0; i < n; i++)
        {
            int a = Math.Max(0, i - 1), b = Math.Min(n - 1, i + 1);
            double dx = s.X(b) - s.X(a), dy = s.Y(b) - s.Y(a);
            double len = Math.Sqrt(dx * dx + dy * dy);
            double nx = lastNx, ny = lastNy;
            if (len > 1e-6)
            {
                dx /= len; dy /= len;
                nx = -dy; ny = dx;
                if (i == 0) { dxStart = dx; dyStart = dy; }
                dxEnd = dx; dyEnd = dy;
            }
            lastNx = nx; lastNy = ny;
            double hw = Math.Max(s.WidthAt(i) / 2, 0.3);
            left[i] = new Point(s.X(i) + nx * hw, s.Y(i) + ny * hw);
            right[i] = new Point(s.X(i) - nx * hw, s.Y(i) - ny * hw);
        }

        c.BeginFigure(left[0], true);
        for (int i = 1; i < n; i++) c.LineTo(left[i]);
        Cap(c, s.X(n - 1), s.Y(n - 1), -dyEnd, dxEnd, dxEnd, dyEnd, Math.Max(s.WidthAt(n - 1) / 2, 0.3));
        for (int i = n - 1; i >= 0; i--) c.LineTo(right[i]);
        Cap(c, s.X(0), s.Y(0), dyStart, -dxStart, -dxStart, -dyStart, Math.Max(s.WidthAt(0) / 2, 0.3));
        c.EndFigure(true);
        return g;
    }

    /// <summary>Meia-circunferência de (centro + n·r) até (centro − n·r), a passar por (centro + d·r).</summary>
    private static void Cap(StreamGeometryContext c, double cx, double cy, double nx, double ny, double dx, double dy, double r)
    {
        const int steps = 8;
        for (int k = 0; k <= steps; k++)
        {
            double th = Math.PI * k / steps;
            double cos = Math.Cos(th), sin = Math.Sin(th);
            c.LineTo(new Point(cx + (nx * cos + dx * sin) * r, cy + (ny * cos + dy * sin) * r));
        }
    }
}
