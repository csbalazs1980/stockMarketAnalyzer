import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { PasswordModule } from 'primeng/password';

import { AuthService } from '../../core/auth/auth.service';

/**
 * The application's own sign-in screen.
 *
 * <p>There is no Keycloak here - no redirect, no realm, no client id, not even a link. The form
 * posts to this application's `/api/v1/auth/login`, and what the backend does with those
 * credentials is none of the browser's business. To the user this is simply where you sign in to
 * Stock Market Analyzer.
 *
 * <p>`returnUrl` is carried by the guard so that a deep link kept behind the guard survives the
 * detour, and `expired` distinguishes "you were signed out" from "you arrived here yourself" -
 * the same page, but not the same thing to say.
 */
@Component({
  selector: 'app-login-page',
  imports: [
    ReactiveFormsModule,
    TranslocoPipe,
    ButtonModule,
    CardModule,
    InputTextModule,
    MessageModule,
    PasswordModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="login-shell">
      <p-card styleClass="login-card">
        <ng-template #header>
          <div class="login-header">
            <i class="pi pi-chart-line"></i>
            <div>
              <h1>{{ 'auth.signIn' | transloco }}</h1>
              <p class="muted">{{ 'auth.loginSubtitle' | transloco }}</p>
            </div>
          </div>
        </ng-template>

        @if (expired) {
          <p-message severity="warn" styleClass="login-banner">
            {{ 'auth.sessionExpired' | transloco }}
          </p-message>
        }

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <div class="field">
            <label for="username">{{ 'auth.username' | transloco }}</label>
            <input
              pInputText
              id="username"
              formControlName="username"
              autocomplete="username"
              autocapitalize="none"
              spellcheck="false"
              [attr.aria-invalid]="invalid('username') || null"
            />
          </div>

          <div class="field">
            <label for="password">{{ 'auth.password' | transloco }}</label>
            <p-password
              inputId="password"
              formControlName="password"
              autocomplete="current-password"
              [feedback]="false"
              [toggleMask]="true"
              [fluid]="true"
              [attr.aria-invalid]="invalid('password') || null"
            />
          </div>

          @if (error(); as message) {
            <p-message severity="error" styleClass="login-banner">{{ message }}</p-message>
          }

          <p-button
            type="submit"
            [label]="'auth.signIn' | transloco"
            icon="pi pi-sign-in"
            [loading]="busy()"
            [disabled]="busy()"
            styleClass="login-submit"
          />
        </form>
      </p-card>
    </div>
  `,
  styles: `
    .login-shell {
      display: flex;
      justify-content: center;
      padding: 2rem 0;
    }

    :host ::ng-deep .login-card {
      width: min(26rem, 100%);
    }

    .login-header {
      display: flex;
      align-items: center;
      gap: 0.85rem;
      padding: 1.25rem 1.25rem 0;
    }

    .login-header i {
      font-size: 1.75rem;
    }

    .login-header h1 {
      margin: 0;
      font-size: 1.25rem;
      font-weight: 600;
    }

    .login-header p {
      margin: 0.15rem 0 0;
      font-size: 0.85rem;
    }

    /* .field itself (flex column, full-width inputs, label styling) is global, in styles.scss. */
    .field {
      margin-bottom: 1rem;
    }

    :host ::ng-deep .login-banner {
      display: block;
      margin-bottom: 1rem;
    }

    :host ::ng-deep .login-submit {
      width: 100%;
    }

    :host ::ng-deep .login-submit .p-button {
      width: 100%;
      justify-content: center;
    }
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  // Read from the activated route rather than from Router.url: during this component's
  // construction the router's own url is still settling, and the snapshot for the route being
  // activated is not.
  private readonly params = inject(ActivatedRoute).snapshot.queryParamMap;

  protected readonly expired = this.params.get('expired') === '1';

  constructor() {
    // Nothing to sign in to when you already are; a bookmarked /login should not be a dead end.
    if (this.auth.authenticated()) {
      void this.router.navigateByUrl(this.returnUrl());
    }
  }

  protected invalid(control: 'username' | 'password'): boolean {
    const field = this.form.controls[control];
    return field.invalid && (field.dirty || field.touched);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.busy.set(true);
    this.error.set(null);

    this.auth.signIn(this.form.getRawValue()).subscribe({
      next: () => {
        this.busy.set(false);
        void this.router.navigateByUrl(this.returnUrl());
      },
      error: (failure: unknown) => {
        this.busy.set(false);
        // 401 is the one failure the user can do something about, so it is the one that gets a
        // specific message. Everything else - the identity provider being down, the network
        // being gone - is not their fault and must not read as "wrong password".
        const status = (failure as { status?: number })?.status;
        this.error.set(
          this.transloco.translate(status === 401 ? 'auth.invalidCredentials' : 'auth.signInFailed'),
        );
      },
    });
  }

  /** Only ever an in-app path: a returnUrl from the query string must not become an open redirect. */
  private returnUrl(): string {
    const requested = this.params.get('returnUrl');
    return requested && requested.startsWith('/') && !requested.startsWith('//') ? requested : '/';
  }
}
