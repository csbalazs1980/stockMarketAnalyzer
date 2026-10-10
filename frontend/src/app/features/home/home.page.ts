import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';

import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-home-page',
  imports: [RouterLink, ButtonModule, CardModule, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="home-hero">
      <h1>{{ 'home.title' | transloco }}</h1>
      <p class="muted">{{ 'home.subtitle' | transloco }}</p>
    </section>

    <div class="home-cards">
      <p-card [header]="'nav.wealth' | transloco">
        <p>{{ 'home.wealthBlurb' | transloco }}</p>
        <p-button
          [label]="'home.openWealth' | transloco"
          icon="pi pi-arrow-right"
          iconPos="right"
          routerLink="/wealth"
        />
      </p-card>

      @if (!user.authenticated()) {
        <p-card [header]="'home.signedOutTitle' | transloco">
          <p>{{ 'home.signedOutBlurb' | transloco }}</p>
          <p-button [label]="'auth.signIn' | transloco" icon="pi pi-sign-in" (onClick)="user.goToLogin()" />
        </p-card>
      }
    </div>
  `,
  styles: `
    .home-hero {
      margin-bottom: 1.5rem;
    }

    .home-hero h1 {
      margin: 0 0 0.25rem;
      font-size: 1.6rem;
      font-weight: 600;
    }

    .home-cards {
      display: grid;
      gap: 1rem;
      grid-template-columns: 1fr;
    }

    @media (min-width: 768px) {
      .home-cards {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
})
export class HomePage {
  protected readonly user = inject(AuthService);
}
