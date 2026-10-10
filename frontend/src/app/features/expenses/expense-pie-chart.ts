import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { EChartsCoreOption } from 'echarts/core';
import { NgxEchartsDirective } from 'ngx-echarts';

import { ShareSlice } from './expense-recurrence';

/**
 * Where one magnitude band ends and the next begins, as a share of the total.
 *
 * Colour here is **not** identity - see the class comment - so three bands is not a shortage. It
 * is the most a one-hue ramp can show on this application's surfaces and still keep every step
 * visibly apart: a mid-grey light surface (#bfbfbf, the user's own 25%-ink Aura) leaves only the
 * dark half of a blue ramp usable, and the validated steps inside it are three.
 */
const BAND_THRESHOLDS = [0.15, 0.05];

/**
 * The bands, biggest share first, validated as ordinal ramps against this application's real
 * surfaces rather than a generic white and black:
 *
 *   light, surface #bfbfbf: all checks pass (light end 2.40:1, every adjacent gap >= 0.06 L)
 *   dark,  surface #18181b: all checks pass (dark end 4.87:1, every adjacent gap >= 0.06 L)
 *
 * The two are stepped for their own surface rather than being one list flipped - on a dark ground
 * "more" reads as *lighter*, because that is the direction away from the surface.
 */
const BANDS = {
  light: ['#104281', '#1c5cab', '#2a78d6'],
  dark: ['#cde2fb', '#86b6ef', '#3987e5'],
};

/** The summed tail, deliberately outside the ramp: it is a bucket, not a magnitude. */
const OTHER_COLOUR = { light: '#52514e', dark: '#c3c2b7' };

/** The dialog's own background, which is what the gaps between slices are painted in. */
const SURFACE = { light: '#bfbfbf', dark: '#18181b' };

/** echarts paints a canvas and inherits nothing from the PrimeNG variables. */
const TEXT = { light: '#262626', dark: '#e4e4e7' };

/** Below this wedge angle a direct label has nowhere to sit; 12 degrees is about 3.3%. */
const MIN_LABEL_ANGLE = 12;

/**
 * Expenses as shares of the total, per period and currency.
 *
 * <p><strong>Colour does not carry identity here, and that is the point.</strong> A slice is one
 * expense, and an expense can carry two categories or none, so there is no category to colour it
 * by - which is the question the user raised, and the honest answer is not to try. What the pie
 * encodes is magnitude, and magnitude takes a single-hue ramp: three bands by share, darkest (on
 * a dark ground, lightest) for the biggest. Two dozen categorical hues would be a rainbow that
 * says nothing, and the same wedge would change colour every time a filter reordered the list.
 *
 * <p>Identity comes from the direct labels on the wedges that are wide enough to hold one, from
 * the tooltip on every wedge, and from the table the dialog is opened on top of - so it is never
 * carried by colour alone. There is deliberately no legend box: with up to 25 named expenses it
 * would be taller than the chart and scroll three entries at a time.
 *
 * <p>Lives in its own component so echarts stays out of the page's bundle until the dialog is
 * actually opened - `provideEchartsCore` loads the library lazily, and nothing here is constructed
 * while the dialog is closed.
 */
@Component({
  selector: 'app-expense-pie-chart',
  imports: [NgxEchartsDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: ` <div echarts class="chart" [options]="options()" [autoResize]="true"></div> `,
  styles: `
    .chart {
      width: 100%;
      height: min(62vh, 30rem);
    }
  `,
})
export class ExpensePieChart {
  readonly slices = input.required<ShareSlice[]>();
  /** What every slice is denominated in - one currency, or the pie would not be a whole. */
  readonly unit = input.required<string>();
  readonly dark = input(false);

  /** "{percent}% of the total", translated by the parent. Single braces: transloco leaves them. */
  readonly shareLabel = input('{percent}%');

  /** "{count} smaller expenses", for the Other slice's tooltip. Empty when nothing was folded. */
  readonly otherNote = input('');

  protected readonly options = computed<EChartsCoreOption>(() => {
    const slices = this.slices();
    const unit = this.unit();
    const mode = this.dark() ? 'dark' : 'light';
    const text = TEXT[mode];
    const bands = BANDS[mode];

    const numbers = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
    const percents = new Intl.NumberFormat(undefined, {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
    });

    const data = slices.map((slice) => ({
      name: slice.name,
      value: slice.value,
      itemStyle: {
        color: slice.other ? OTHER_COLOUR[mode] : bands[bandOf(slice.share)],
        // A 2px gap in the surface colour, so two neighbours sharing a band are still two.
        borderColor: SURFACE[mode],
        borderWidth: 2,
      },
    }));

    return {
      backgroundColor: 'transparent',
      textStyle: { color: text },
      tooltip: {
        trigger: 'item',
        formatter: (params: unknown) => {
          const index = (params as { dataIndex?: number }).dataIndex ?? -1;
          const slice = slices[index];
          if (!slice) {
            return '';
          }
          const share = this.shareLabel().replace(
            '{percent}',
            percents.format(slice.share * 100),
          );
          const note = slice.other && this.otherNote() ? `<br><small>${this.otherNote()}</small>` : '';
          return `<b>${escapeHtml(slice.name)}</b><br>${numbers.format(slice.value)} ${unit}<br>${share}${note}`;
        },
      },
      series: [
        {
          type: 'pie',
          // Not a donut: the user asked for a pie, and a hole would only make the thin wedges at
          // the tail thinner still.
          radius: '66%',
          center: ['50%', '52%'],
          // The data arrives biggest first and must stay that way - echarts would otherwise
          // reorder it and the bands would stop running round the circle in one direction.
          sort: 'none',
          avoidLabelOverlap: true,
          minShowLabelAngle: MIN_LABEL_ANGLE,
          data,
          label: {
            color: text,
            formatter: '{b}\n{d}%',
            lineHeight: 16,
          },
          labelLine: { length: 10, length2: 14, lineStyle: { color: text, opacity: 0.45 } },
          emphasis: {
            // The hover cue is the wedge lifting, not a colour change: a band that changed hue on
            // hover would be saying something about magnitude that is not true.
            scaleSize: 6,
            itemStyle: { shadowBlur: 8, shadowColor: 'rgba(0, 0, 0, 0.25)' },
          },
        },
      ],
    };
  });
}

/** Which magnitude band a share falls in: 0 is the biggest. */
function bandOf(share: number): number {
  const index = BAND_THRESHOLDS.findIndex((threshold) => share >= threshold);
  return index === -1 ? BAND_THRESHOLDS.length : index;
}

/** Expense names are typed by the user and go into a tooltip that renders HTML. */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
