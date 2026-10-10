import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { EChartsCoreOption } from 'echarts/core';
import { NgxEchartsDirective } from 'ngx-echarts';

import { MonthPoint } from './dividend-monthly.model';

/** Beyond this many months the labels stop fitting side by side and are stood on end. */
const ROTATE_LABELS_ABOVE = 12;

/** Beyond this many months even rotated labels crowd, so the chart gains a zoom slider. */
const ZOOM_ABOVE = 24;

/**
 * Dividend income per calendar month.
 *
 * Lives in its own component so echarts stays out of the grid page's bundle until the dialog is
 * actually opened - `provideEchartsCore` already loads the library lazily, and nothing here is
 * constructed while the dialog is closed.
 */
@Component({
  selector: 'app-dividend-monthly-chart',
  imports: [NgxEchartsDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: ` <div echarts class="chart" [options]="options()" [autoResize]="true"></div> `,
  styles: `
    .chart {
      width: 100%;
      height: min(60vh, 26rem);
    }
  `,
})
export class DividendMonthlyChart {
  readonly points = input.required<MonthPoint[]>();
  /** What the y-axis is denominated in - the filtered currency, or the valuation currency. */
  readonly unit = input.required<string>();
  readonly dark = input(false);
  /** Series name and the "lower bound" note, translated by the parent. */
  readonly seriesLabel = input('');
  readonly partialLabel = input('');

  protected readonly options = computed<EChartsCoreOption>(() => {
    const points = this.points();
    const unit = this.unit();
    const partialLabel = this.partialLabel();

    const rotate = points.length > ROTATE_LABELS_ABOVE ? 90 : 0;
    const zoom = points.length > ZOOM_ABOVE;

    // echarts draws onto a canvas, so it inherits nothing from the PrimeNG theme.
    const text = this.dark() ? '#e4e4e7' : '#262626';
    const lines = this.dark() ? '#3f3f46' : '#8a8a8a';

    const formatter = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
    const partialAt = new Set(points.filter((point) => point.partial).map((point) => point.key));

    return {
      backgroundColor: 'transparent',
      textStyle: { color: text },
      grid: {
        left: 8,
        right: 16,
        top: 16,
        bottom: zoom ? 56 : 8,
        containLabel: true,
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: unknown) => {
          const rows = Array.isArray(params) ? params : [params];
          const first = rows[0] as { axisValue?: string; value?: number | null } | undefined;
          if (!first) {
            return '';
          }
          const value = first.value ?? null;
          const shown = value === null ? '—' : `${formatter.format(value)} ${unit}`;
          const note =
            partialAt.has(first.axisValue ?? '') && partialLabel ? `<br><small>${partialLabel}</small>` : '';
          return `${first.axisValue ?? ''}<br><b>${shown}</b>${note}`;
        },
      },
      xAxis: {
        type: 'category',
        data: points.map((point) => point.key),
        axisLabel: { color: text, rotate, hideOverlap: rotate === 0 },
        axisTick: { alignWithLabel: true },
        axisLine: { lineStyle: { color: lines } },
      },
      yAxis: {
        type: 'value',
        name: unit,
        nameTextStyle: { color: text },
        axisLabel: {
          color: text,
          formatter: (value: number) => formatter.format(value),
        },
        splitLine: { lineStyle: { color: lines } },
      },
      dataZoom: zoom
        ? [
            { type: 'inside' },
            { type: 'slider', bottom: 8, height: 18, borderColor: lines, textStyle: { color: text } },
          ]
        : [],
      series: [
        {
          type: 'bar',
          name: this.seriesLabel(),
          // A month with nothing in it is a gap, not a zero-height bar.
          data: points.map((point) => point.value),
          barMaxWidth: 44,
          itemStyle: { color: '#6366f1', borderRadius: [3, 3, 0, 0] },
        },
      ],
    };
  });
}
