package com.stockmarketanalyzer.backend.price.service;

import com.stockmarketanalyzer.backend.common.ResourceNotFoundException;
import com.stockmarketanalyzer.backend.instrument.domain.Instrument;
import com.stockmarketanalyzer.backend.instrument.service.InstrumentService;
import com.stockmarketanalyzer.backend.price.domain.PriceBar;
import com.stockmarketanalyzer.backend.price.repository.PriceBarRepository;
import com.stockmarketanalyzer.backend.price.repository.PriceStatistics;
import com.stockmarketanalyzer.backend.price.web.dto.PriceBarRequest;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PriceBarService {

    private static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);

    private final PriceBarRepository repository;
    private final InstrumentService instrumentService;

    public Page<PriceBar> findBars(String symbol, LocalDate from, LocalDate to, Pageable pageable) {
        Instrument instrument = instrumentService.getBySymbol(symbol);
        DateRange range = DateRange.of(from, to);
        return repository.findByInstrumentAndTradeDateBetween(instrument, range.from(), range.to(), pageable);
    }

    public PriceBar findLatest(String symbol) {
        Instrument instrument = instrumentService.getBySymbol(symbol);
        return repository
                .findFirstByInstrumentOrderByTradeDateDesc(instrument)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No price data stored for symbol '%s'".formatted(instrument.getSymbol())));
    }

    public PriceStatistics statistics(String symbol, LocalDate from, LocalDate to) {
        Instrument instrument = instrumentService.getBySymbol(symbol);
        DateRange range = DateRange.of(from, to);
        return repository.statistics(instrument, range.from(), range.to());
    }

    /**
     * Inserts the given bars, replacing any bar already stored for the same trading day.
     */
    @Transactional
    public List<PriceBar> upsert(String symbol, List<PriceBarRequest> requests) {
        Instrument instrument = instrumentService.getBySymbol(symbol);
        log.debug("Upserting {} price bar(s) for {}", requests.size(), instrument.getSymbol());
        return requests.stream()
                .map(request -> {
                    validate(request);
                    PriceBar bar = repository
                            .findByInstrumentAndTradeDate(instrument, request.tradeDate())
                            .orElseGet(() -> new PriceBar(instrument, request.tradeDate()));
                    bar.setOpen(request.open());
                    bar.setHigh(request.high());
                    bar.setLow(request.low());
                    bar.setClose(request.close());
                    bar.setAdjustedClose(request.adjustedClose() != null ? request.adjustedClose() : request.close());
                    bar.setVolume(request.volume());
                    return repository.save(bar);
                })
                .toList();
    }

    @Transactional
    public long deleteRange(String symbol, LocalDate from, LocalDate to) {
        Instrument instrument = instrumentService.getBySymbol(symbol);
        DateRange range = DateRange.of(from, to);
        long deleted = repository.deleteByInstrumentAndTradeDateBetween(instrument, range.from(), range.to());
        log.info("Deleted {} price bar(s) for {} between {} and {}",
                deleted, instrument.getSymbol(), range.from(), range.to());
        return deleted;
    }

    private static void validate(PriceBarRequest request) {
        if (request.high().compareTo(request.low()) < 0) {
            throw new IllegalArgumentException(
                    "High must be greater than or equal to low for %s".formatted(request.tradeDate()));
        }
        if (request.tradeDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Trade date %s is in the future".formatted(request.tradeDate()));
        }
    }

    private record DateRange(LocalDate from, LocalDate to) {

        static DateRange of(LocalDate from, LocalDate to) {
            LocalDate start = from != null ? from : MIN_DATE;
            LocalDate end = to != null ? to : LocalDate.now();
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("'from' (%s) must not be after 'to' (%s)".formatted(start, end));
            }
            return new DateRange(start, end);
        }
    }
}
