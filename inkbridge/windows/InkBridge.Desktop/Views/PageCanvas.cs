using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
using InkBridge.Model;

namespace InkBridge.Views;

/// <summary>
/// Mostra uma página. Roda do rato = zoom; arrastar = mover; duplo clique = ajustar.
/// </summary>
public sealed class PageCanvas : Control
{
    public Page? Page { get; private set; }
    public (double x, double y)? Hover { get; set; }
    public bool FitWidth { get; set; }

    private double _zoom = 1;
    private Vector _pan;
    private Point? _dragStart;
    private Vector _panStart;

    private static readonly IBrush Desk = new SolidColorBrush(Color.FromRgb(0xE4, 0xE4, 0xE4));
    private static readonly IBrush Shadow = new SolidColorBrush(Color.FromArgb(0x26, 0, 0, 0));
    private static readonly IPen CursorPen = new Pen(new SolidColorBrush(Color.FromRgb(0x25, 0x63, 0xEB)), 2);
    private static readonly IBrush CursorFill = new SolidColorBrush(Color.FromArgb(0x40, 0x25, 0x63, 0xEB));

    public PageCanvas()
    {
        ClipToBounds = true;
        Focusable = true;
    }

    public void SetPage(Page? page, bool resetView)
    {
        Page = page;
        if (resetView) ResetView();
        InvalidateVisual();
    }

    public void ResetView()
    {
        _zoom = 1;
        _pan = default;
        InvalidateVisual();
    }

    private (double scale, double ox, double oy) Transform()
    {
        var p = Page!;
        const double margin = 20;
        double w = Bounds.Width, h = Bounds.Height;
        double fit = FitWidth ? (w - 2 * margin) / p.W : Math.Min((w - 2 * margin) / p.W, (h - 2 * margin) / p.H);
        double s = Math.Max(fit, 0.01) * _zoom;
        double ox = (w - p.W * s) / 2 + _pan.X;
        double oy = (FitWidth ? margin : (h - p.H * s) / 2) + _pan.Y;
        return (s, ox, oy);
    }

    public override void Render(DrawingContext ctx)
    {
        ctx.DrawRectangle(Desk, null, new Rect(Bounds.Size));
        var p = Page;
        if (p == null) return;
        var (s, ox, oy) = Transform();
        ctx.DrawRectangle(Shadow, null, new Rect(ox + 3, oy + 4, p.W * s, p.H * s));
        using (ctx.PushTransform(Matrix.CreateScale(s, s) * Matrix.CreateTranslation(ox, oy)))
        {
            PageRenderer.DrawPage(ctx, p);
        }
        if (Hover is { } hv)
        {
            var c = new Point(ox + hv.x * s, oy + hv.y * s);
            ctx.DrawEllipse(CursorFill, CursorPen, c, 6, 6);
        }
    }

    protected override void OnPointerWheelChanged(PointerWheelEventArgs e)
    {
        if (Page == null) return;
        var pos = e.GetPosition(this);
        var (s, ox, oy) = Transform();
        double px = (pos.X - ox) / s, py = (pos.Y - oy) / s;
        double factor = e.Delta.Y > 0 ? 1.15 : 1 / 1.15;
        _zoom = Math.Clamp(_zoom * factor, 0.2, 12);
        // mantém o ponto debaixo do rato no mesmo sítio
        var (s2, ox2, oy2) = Transform();
        _pan += new Vector(pos.X - (ox2 + px * s2), pos.Y - (oy2 + py * s2));
        InvalidateVisual();
        e.Handled = true;
    }

    protected override void OnPointerPressed(PointerPressedEventArgs e)
    {
        if (e.ClickCount == 2) { ResetView(); return; }
        _dragStart = e.GetPosition(this);
        _panStart = _pan;
        e.Pointer.Capture(this);
    }

    protected override void OnPointerMoved(PointerEventArgs e)
    {
        if (_dragStart is not { } start) return;
        _pan = _panStart + (e.GetPosition(this) - start);
        InvalidateVisual();
    }

    protected override void OnPointerReleased(PointerReleasedEventArgs e)
    {
        _dragStart = null;
        e.Pointer.Capture(null);
    }
}
