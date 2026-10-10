/**
 * The rows-per-page choices every paginated table offers, smallest first; the first is the default.
 *
 * One list for the whole app so the tables agree. The ceiling matches the backend's
 * `spring.data.web.pageable.max-page-size` (1000): a larger choice here would silently be served
 * a 1000-row page by Spring while the paginator counted pages of a different size.
 */
export const PAGE_SIZE_OPTIONS: number[] = [100, 250, 500, 1000];
