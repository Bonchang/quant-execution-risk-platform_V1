package com.bonchang.qerp.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bonchang.qerp.security.JwtTokenService;
import com.bonchang.qerp.execution.OrderExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OrderControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("qerp")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("market-data.execution-slippage-bps", () -> "0");
        registry.add("market-data.stale-threshold-seconds", () -> "20");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private OrderExecutionService orderExecutionService;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenService.generateToken(User.withUsername("admin").password("ignored").roles("ADMIN").build());
        jdbcTemplate.execute("DELETE FROM outbox_event");
        jdbcTemplate.execute("DELETE FROM cash_ledger_entry");
        jdbcTemplate.execute("DELETE FROM risk_check_result");
        jdbcTemplate.execute("DELETE FROM portfolio_snapshot");
        jdbcTemplate.execute("DELETE FROM fill");
        jdbcTemplate.execute("DELETE FROM orders");
        jdbcTemplate.execute("DELETE FROM position");
        jdbcTemplate.execute("DELETE FROM market_data_run");
        jdbcTemplate.execute("DELETE FROM market_quote");
        jdbcTemplate.execute("DELETE FROM market_price");
        jdbcTemplate.execute("DELETE FROM strategy_run");
        jdbcTemplate.execute("DELETE FROM app_user");
        jdbcTemplate.execute("DELETE FROM instrument");
        jdbcTemplate.execute("DELETE FROM cash_balance");
        jdbcTemplate.execute("DELETE FROM account");
    }

    @Test
    void createOrder_success() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId);

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "10.000000", "MARKET", null, "client-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.accountId").value(findAccountIdByStrategyRun(strategyRunId)))
                .andExpect(jsonPath("$.strategyRunId").value(strategyRunId))
                .andExpect(jsonPath("$.instrumentId").value(instrumentId))
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(10.0))
                .andExpect(jsonPath("$.remainingQuantity").value(0.0))
                .andExpect(jsonPath("$.clientOrderId").value("client-001"));

        Integer fillCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fill",
                Integer.class
        );
        Integer positionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM position",
                Integer.class
        );
        Integer snapshotCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolio_snapshot",
                Integer.class
        );

        org.assertj.core.api.Assertions.assertThat(fillCount).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(positionCount).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(snapshotCount).isEqualTo(1);
    }

    @Test
    void createOrder_marketOrder_persistsSingleFillAndUpdatesPosition() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId);

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "11.000000", "MARKET", null, "market-multi-fill-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(11.0))
                .andExpect(jsonPath("$.remainingQuantity").value(0.0));

        Integer fillCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fill WHERE order_id = (SELECT id FROM orders WHERE client_order_id = 'market-multi-fill-001')",
                Integer.class
        );
        org.assertj.core.api.Assertions.assertThat(fillCount).isEqualTo(1);

        String netQuantity = jdbcTemplate.queryForObject(
                "SELECT TO_CHAR(net_quantity, 'FM9999999990.000000') FROM position WHERE strategy_run_id = ? AND instrument_id = ?",
                String.class,
                strategyRunId,
                instrumentId
        );
        org.assertj.core.api.Assertions.assertThat(netQuantity).isEqualTo("11.000000");
    }

    @Test
    void createOrder_duplicateClientOrderIdPerStrategyRun_rejected() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId);

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "10.000000", "MARKET", null, "dup-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict());
    }

    @Test
    void createOrder_overLimit_rejectedAndNoFill() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId);

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "1500.000000", "MARKET", null, "risk-reject-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        Integer fillCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fill", Integer.class);
        org.assertj.core.api.Assertions.assertThat(fillCount).isZero();
    }

    @Test
    void createOrder_sellOrder_reducingExposure_isNotRejectedByInstrumentLimit() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "100.000000");

        createMarketOrder(strategyRunId, instrumentId, "BUY", "1000.000000", "exposure-buy-001");
        createMarketOrder(strategyRunId, instrumentId, "BUY", "1000.000000", "exposure-buy-002");
        insertMarketPriceTomorrow(instrumentId, "95.000000");

        String sellPayload = orderPayload(strategyRunId, instrumentId, "SELL", "500.000000", "LIMIT", "90.000000", "exposure-sell-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sellPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(500.0))
                .andExpect(jsonPath("$.remainingQuantity").value(0.0));
    }

    @Test
    void createOrder_limitBuy_fillsWhenMarketPriceAtOrBelowLimit() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "105.000000");

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "10.000000", "LIMIT", "105.000000", "limit-partial-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.limitPrice").value(105.0))
                .andExpect(jsonPath("$.filledQuantity").value(10.0))
                .andExpect(jsonPath("$.remainingQuantity").value(0.0));

        Integer fillCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fill WHERE order_id = (SELECT id FROM orders WHERE client_order_id = 'limit-partial-001')",
                Integer.class
        );
        org.assertj.core.api.Assertions.assertThat(fillCount).isEqualTo(1);
    }

    @Test
    void createOrder_limitBuy_staysApprovedWhenMarketPriceAboveLimit() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "110.000000");
        insertMarketQuote(instrumentId, "110.000000", "109.500000", "110.500000", "1.500000");

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "10.000000", "LIMIT", "100.000000", "limit-buy-open-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WORKING"))
                .andExpect(jsonPath("$.filledQuantity").value(0))
                .andExpect(jsonPath("$.remainingQuantity").value(10.0));

        Integer fillCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fill", Integer.class);
        Integer positionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM position", Integer.class);
        org.assertj.core.api.Assertions.assertThat(fillCount).isZero();
        org.assertj.core.api.Assertions.assertThat(positionCount).isEqualTo(1);
    }

    @Test
    void createOrder_limitSell_fillsWhenMarketPriceAtOrAboveLimit() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "105.000000");
        insertMarketQuote(instrumentId, "105.000000", "104.500000", "105.500000", "1.500000");
        createMarketOrder(strategyRunId, instrumentId, "BUY", "10.000000", "limit-sell-fill-buy-001");

        String payload = orderPayload(strategyRunId, instrumentId, "SELL", "7.000000", "LIMIT", "100.000000", "limit-sell-fill-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(7.0))
                .andExpect(jsonPath("$.remainingQuantity").value(0.0));
    }

    @Test
    void createOrder_sellWithoutPosition_rejectedAndNoFill() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "105.000000");
        insertMarketQuote(instrumentId, "105.000000", "104.500000", "105.500000", "1.500000");

        String payload = orderPayload(strategyRunId, instrumentId, "SELL", "7.000000", "MARKET", null, "sell-no-position-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        Integer fillCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fill", Integer.class);
        Integer positionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM position", Integer.class);
        org.assertj.core.api.Assertions.assertThat(fillCount).isZero();
        org.assertj.core.api.Assertions.assertThat(positionCount).isEqualTo(1);
    }

    @Test
    void createOrder_sellBeyondAvailablePosition_rejectedAndNoFill() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "105.000000");
        insertMarketQuote(instrumentId, "105.000000", "104.500000", "105.500000", "1.500000");
        createMarketOrder(strategyRunId, instrumentId, "BUY", "2.000000", "sell-too-much-buy-001");

        String payload = orderPayload(strategyRunId, instrumentId, "SELL", "5.000000", "MARKET", null, "sell-too-much-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        Integer fillCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fill WHERE order_id = (SELECT id FROM orders WHERE client_order_id = 'sell-too-much-001')",
                Integer.class
        );
        String netQuantity = jdbcTemplate.queryForObject(
                "SELECT TO_CHAR(net_quantity, 'FM9999999990.000000') FROM position WHERE strategy_run_id = ? AND instrument_id = ?",
                String.class,
                strategyRunId,
                instrumentId
        );

        org.assertj.core.api.Assertions.assertThat(fillCount).isZero();
        org.assertj.core.api.Assertions.assertThat(netQuantity).isEqualTo("2.000000");
    }

    @Test
    void createOrder_limitSell_staysApprovedWhenMarketPriceBelowLimit() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "95.000000");
        insertMarketQuote(instrumentId, "95.000000", "94.500000", "95.500000", "-1.000000");
        createMarketOrder(strategyRunId, instrumentId, "BUY", "10.000000", "limit-sell-open-buy-001");

        String payload = orderPayload(strategyRunId, instrumentId, "SELL", "7.000000", "LIMIT", "100.000000", "limit-sell-open-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WORKING"))
                .andExpect(jsonPath("$.filledQuantity").value(0))
                .andExpect(jsonPath("$.remainingQuantity").value(7.0));
    }

    @Test
    void createOrder_limitOrder_withoutLimitPrice_badRequest() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "105.000000");

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "10.000000", "LIMIT", null, "limit-invalid-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limitPrice is required for LIMIT order"));
    }

    @Test
    void createOrder_marketOrder_withLimitPrice_badRequest() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "105.000000");

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "10.000000", "MARKET", "100.000000", "market-invalid-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limitPrice must be null for MARKET order"));
    }

    @Test
    void createOrder_missingStrategyRun_badRequest() throws Exception {
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId);

        String payload = objectMapper.writeValueAsString(Map.of(
                "accountId", insertAccount(),
                "strategyRunId", 999999L,
                "instrumentId", instrumentId,
                "side", "BUY",
                "quantity", "10.000000",
                "orderType", "MARKET",
                "timeInForce", "DAY",
                "clientOrderId", "bad-strategy-001"
        ));

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("strategyRunId not found"));
    }

    @Test
    void ingestMarketData_withoutApiKey_returnsAcceptedWithFailureMessage() throws Exception {
        mockMvc.perform(authorizedPost("/market-data/ingest"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.source").value("DEMO_SYNTHETIC"))
                .andExpect(jsonPath("$.totalInstruments").value(0))
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(0))
                .andExpect(jsonPath("$.runStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.updatedSymbols").isArray())
                .andExpect(jsonPath("$.failures").isArray());
    }

    @Test
    void marketDataStatus_afterIngest_exposesLastResult() throws Exception {
        mockMvc.perform(authorizedPost("/market-data/ingest"))
                .andExpect(status().isAccepted());

        mockMvc.perform(authorizedGet("/market-data/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.apiKeyConfigured").value(false))
                .andExpect(jsonPath("$.source").value("DEMO_SYNTHETIC"))
                .andExpect(jsonPath("$.lastResult.successCount").value(0))
                .andExpect(jsonPath("$.lastResult.failureCount").value(0))
                .andExpect(jsonPath("$.lastResult.failures").isArray());
    }

    @Test
    void dashboardOverview_includesLatestPortfolioSummaryAndSnapshots() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "105.000000");
        insertMarketQuote(instrumentId, "106.000000", "106.000000", "106.000000", "0.900000");

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "10.000000", "MARKET", null, "portfolio-overview-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(authorizedGet("/dashboard/overview?limit=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolioSummary.strategyRunId").value(strategyRunId))
                .andExpect(jsonPath("$.portfolioSummary.totalMarketValue").value(1060.0))
                .andExpect(jsonPath("$.portfolioSummary.realizedPnl").value(0.0))
                .andExpect(jsonPath("$.portfolioSummary.unrealizedPnl").value(0.0))
                .andExpect(jsonPath("$.quoteSummary.totalQuotes").value(1))
                .andExpect(jsonPath("$.recentQuotes[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.recentPortfolioSnapshots[0].strategyRunId").value(strategyRunId));
    }

    @Test
    void refreshPortfolioSnapshots_usesLatestMarketPriceForUnrealizedPnl() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "105.000000");
        insertMarketQuote(instrumentId, "105.000000", "105.000000", "105.000000", "0.900000");

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "10.000000", "MARKET", null, "portfolio-refresh-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        insertMarketQuote(instrumentId, "120.000000", "120.000000", "120.000000", "2.500000");

        mockMvc.perform(authorizedPost("/dashboard/portfolio-snapshots/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyRunCount").value(1));

        mockMvc.perform(authorizedGet("/dashboard/overview?limit=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolioSummary.totalMarketValue").value(1200.0))
                .andExpect(jsonPath("$.portfolioSummary.realizedPnl").value(0.0))
                .andExpect(jsonPath("$.portfolioSummary.unrealizedPnl").value(150.0))
                .andExpect(jsonPath("$.portfolioSummary.totalPnl").value(150.0));
    }

    @Test
    void createOrder_sellFill_generatesRealizedPnlByAverageCostBeforeSell() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "100.000000");
        insertMarketQuote(instrumentId, "100.000000", "100.000000", "100.000000", "0.500000");

        String buyPayload = orderPayload(strategyRunId, instrumentId, "BUY", "10.000000", "MARKET", null, "realized-buy-001");
        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buyPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"));

        insertMarketQuote(instrumentId, "120.000000", "120.000000", "120.000000", "3.500000");

        String sellPayload = orderPayload(strategyRunId, instrumentId, "SELL", "4.000000", "LIMIT", "110.000000", "realized-sell-001");
        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sellPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"));

        mockMvc.perform(authorizedGet("/dashboard/overview?limit=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolioSummary.realizedPnl").value(80.0))
                .andExpect(jsonPath("$.portfolioSummary.unrealizedPnl").value(120.0))
                .andExpect(jsonPath("$.portfolioSummary.totalPnl").value(200.0))
                .andExpect(jsonPath("$.portfolioSummary.totalMarketValue").value(720.0));
    }

    @Test
    void createOrder_marketBuy_usesLatestAskQuotePrice() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "100.000000");
        insertMarketQuote(instrumentId, "101.000000", "100.800000", "101.200000", "1.100000");

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "5.000000", "MARKET", null, "quote-buy-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"));

        BigDecimal fillPrice = jdbcTemplate.queryForObject(
                "SELECT fill_price FROM fill WHERE order_id = (SELECT id FROM orders WHERE client_order_id = 'quote-buy-001')",
                BigDecimal.class
        );
        org.assertj.core.api.Assertions.assertThat(fillPrice).isEqualByComparingTo("101.200000");
    }

    @Test
    void workingLimitOrder_isReevaluatedAfterQuoteUpdate() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "110.000000");
        insertMarketQuote(instrumentId, "110.000000", "109.500000", "110.500000", "1.100000");

        String payload = orderPayload(strategyRunId, instrumentId, "BUY", "5.000000", "LIMIT", "100.000000", "working-reprice-001");

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WORKING"));

        insertMarketQuote(instrumentId, "99.500000", "99.200000", "99.800000", "-0.500000");
        orderExecutionService.reevaluateWorkingOrdersForInstrument(instrumentId);

        mockMvc.perform(authorizedGet("/orders/" + findOrderId("working-reprice-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(5.0))
                .andExpect(jsonPath("$.remainingQuantity").value(0.0));
    }

    @Test
    void marketDataHealth_marksStaleQuotes() throws Exception {
        Long instrumentId = insertInstrument();
        insertMarketQuote(instrumentId, "100.000000", "99.500000", "100.500000", "0.100000", "2026-04-04T00:00:00");

        mockMvc.perform(authorizedGet("/market-data/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.staleQuoteCount").value(1))
                .andExpect(jsonPath("$.staleSymbols[0]").value("AAPL"));
    }

    @Test
    void marketDataQuotes_returnsQuoteListWithoutServerError() throws Exception {
        Long instrumentId = insertInstrument();
        insertMarketQuote(instrumentId, "100.000000", "99.500000", "100.500000", "0.100000");

        mockMvc.perform(authorizedGet("/market-data/quotes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].market").value("NASDAQ"));
    }

    @Test
    void marketDataQuoteBySymbol_returnsQuoteWithoutServerError() throws Exception {
        Long instrumentId = insertInstrument();
        insertMarketQuote(instrumentId, "100.000000", "99.500000", "100.500000", "0.100000");

        mockMvc.perform(authorizedGet("/market-data/quotes/aapl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.lastPrice").value(100.0));
    }

    @Test
    void getOrder_returnsDetailShapeWithAuditCollections() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "100.000000");
        insertMarketQuote(instrumentId, "100.000000", "100.000000", "100.000000", "0.100000");

        createMarketOrder(strategyRunId, instrumentId, "BUY", "4.000000", "detail-order-001");
        Long orderId = findOrderId("detail-order-001");

        mockMvc.perform(authorizedGet("/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCode").exists())
                .andExpect(jsonPath("$.strategyName").value("mvp-strategy"))
                .andExpect(jsonPath("$.instrumentSymbol").value("AAPL"))
                .andExpect(jsonPath("$.fills[0].fillPrice").value(100.0))
                .andExpect(jsonPath("$.riskChecks[0].ruleName").exists())
                .andExpect(jsonPath("$.outboxEvents[0].eventType").exists())
                .andExpect(jsonPath("$.cashLedgerEntries[0].entryType").exists());
    }

    @Test
    void dashboardTimeline_returnsMixedOperationalEvents() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "100.000000");
        insertMarketQuote(instrumentId, "100.000000", "100.000000", "100.000000", "0.100000");
        createMarketOrder(strategyRunId, instrumentId, "BUY", "3.000000", "timeline-order-001");

        mockMvc.perform(authorizedGet("/dashboard/timeline?limit=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].category").exists())
                .andExpect(jsonPath("$.events[*].category").isArray());
    }

    @Test
    void researchRunDetail_includesArtifactAvailabilityAndParsedRows() throws Exception {
        mockMvc.perform(get("/research/runs/demo-sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactAvailability.equityCurveCsv").value(true))
                .andExpect(jsonPath("$.equityCurveRows[0].price_date").value("2026-04-01"))
                .andExpect(jsonPath("$.tradeRows[0].trade").value(0.3));
    }

    @Test
    void appHome_isPublicAndIncludesFeaturedStocks() throws Exception {
        Long strategyRunId = insertStrategyRun();
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "103.000000");
        insertMarketQuote(instrumentId, "104.000000", "103.800000", "104.200000", "1.400000");
        createMarketOrder(strategyRunId, instrumentId, "BUY", "4.000000", "app-home-order-001");

        mockMvc.perform(get("/app/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetSummary.totalAssets").exists())
                .andExpect(jsonPath("$.featuredStocks[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.marketConnection.status").exists());
    }

    @Test
    void appHome_emptyStateStillReturns200() throws Exception {
        mockMvc.perform(get("/app/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestAvailable").value(true))
                .andExpect(jsonPath("$.assetSummary.ownerName").value("게스트 세션을 시작해 주세요"))
                .andExpect(jsonPath("$.featuredStocks").isArray())
                .andExpect(jsonPath("$.highlights[0].title").value("내 자산"));
    }

    @Test
    void appStockDetail_combinesQuoteSignalAndTradeContext() throws Exception {
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "103.000000");
        insertMarketQuote(instrumentId, "104.000000", "103.800000", "104.200000", "1.400000");
        String guestToken = issueGuestSessionToken();

        mockMvc.perform(get("/app/stocks/AAPL").header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock.symbol").value("AAPL"))
                .andExpect(jsonPath("$.quantInsight.headline").exists())
                .andExpect(jsonPath("$.riskSummary.estimatedBuyPrice").value(104.2))
                .andExpect(jsonPath("$.tradeContext.accountCode").exists())
                .andExpect(jsonPath("$.tradeContext.strategyName").value("consumer-default"));
    }

    @Test
    void appPortfolio_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/app/portfolio"))
                .andExpect(status().isForbidden());
    }

    @Test
    void appPortfolio_returnsHoldingsForGuestSession() throws Exception {
        Long instrumentId = insertInstrument();
        insertMarketPrice(instrumentId, "100.000000");
        insertMarketQuote(instrumentId, "101.000000", "100.800000", "101.200000", "1.000000");
        String guestToken = issueGuestSessionToken();

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "symbol", "AAPL",
                                "side", "BUY",
                                "quantity", "3.000000",
                                "orderType", "MARKET",
                                "timeInForce", "DAY"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"));

        mockMvc.perform(get("/app/portfolio").header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.accountCode").exists())
                .andExpect(jsonPath("$.holdings[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.assetSummary.totalAssets").exists());
    }

    private Long insertStrategyRun() {
        Long accountId = insertAccount();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO strategy_run(account_id, strategy_name, run_at, parameters_json)
                VALUES (?, 'mvp-strategy', NOW(), '{}')
                RETURNING id
                """,
                Long.class,
                accountId
        );
    }

    private Long insertAccount() {
        Long accountId = jdbcTemplate.queryForObject(
                """
                INSERT INTO account(account_code, owner_name, base_currency, created_at)
                VALUES ('ACC-' || substr(md5(random()::text), 1, 8), 'Test Owner', 'USD', NOW())
                RETURNING id
                """,
                Long.class
        );
        jdbcTemplate.update(
                """
                INSERT INTO cash_balance(account_id, available_cash, reserved_cash, updated_at)
                VALUES (?, 1000000.000000, 0.000000, NOW())
                """,
                accountId
        );
        return accountId;
    }

    private Long findAccountIdByStrategyRun(Long strategyRunId) {
        return jdbcTemplate.queryForObject("SELECT account_id FROM strategy_run WHERE id = ?", Long.class, strategyRunId);
    }

    private Long insertInstrument() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO instrument(symbol, name, market)
                VALUES ('AAPL', 'Apple Inc.', 'NASDAQ')
                RETURNING id
                """,
                Long.class
        );
    }

    private void insertMarketPrice(Long instrumentId) {
        insertMarketPrice(instrumentId, "105.000000");
    }

    private void insertMarketPrice(Long instrumentId, String closePrice) {
        jdbcTemplate.update(
                """
                INSERT INTO market_price(instrument_id, price_date, open_price, high_price, low_price, close_price, volume)
                VALUES (?, CURRENT_DATE, 100.000000, 110.000000, 95.000000, ?, 1000)
                """,
                instrumentId,
                new java.math.BigDecimal(closePrice)
        );
    }

    private void insertMarketPriceTomorrow(Long instrumentId, String closePrice) {
        jdbcTemplate.update(
                """
                INSERT INTO market_price(instrument_id, price_date, open_price, high_price, low_price, close_price, volume)
                VALUES (?, CURRENT_DATE + 1, 100.000000, 125.000000, 95.000000, ?, 1000)
                """,
                instrumentId,
                new java.math.BigDecimal(closePrice)
        );
    }

    private void insertMarketQuote(Long instrumentId, String lastPrice, String bidPrice, String askPrice, String changePercent) {
        insertMarketQuote(instrumentId, lastPrice, bidPrice, askPrice, changePercent, null);
    }

    private void insertMarketQuote(
            Long instrumentId,
            String lastPrice,
            String bidPrice,
            String askPrice,
            String changePercent,
            String receivedAtIso
    ) {
        if (receivedAtIso == null) {
            jdbcTemplate.update(
                    """
                    INSERT INTO market_quote(instrument_id, quote_time, last_price, bid_price, ask_price, change_percent, source, received_at)
                    VALUES (?, NOW(), ?, ?, ?, ?, 'TEST', NOW())
                    ON CONFLICT (instrument_id) DO UPDATE
                        SET quote_time = EXCLUDED.quote_time,
                            last_price = EXCLUDED.last_price,
                            bid_price = EXCLUDED.bid_price,
                            ask_price = EXCLUDED.ask_price,
                            change_percent = EXCLUDED.change_percent,
                            source = EXCLUDED.source,
                            received_at = EXCLUDED.received_at
                    """,
                    instrumentId,
                    new BigDecimal(lastPrice),
                    new BigDecimal(bidPrice),
                    new BigDecimal(askPrice),
                    new BigDecimal(changePercent)
            );
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO market_quote(instrument_id, quote_time, last_price, bid_price, ask_price, change_percent, source, received_at)
                VALUES (?, ?, ?, ?, ?, ?, 'TEST', ?)
                ON CONFLICT (instrument_id) DO UPDATE
                    SET quote_time = EXCLUDED.quote_time,
                        last_price = EXCLUDED.last_price,
                        bid_price = EXCLUDED.bid_price,
                        ask_price = EXCLUDED.ask_price,
                        change_percent = EXCLUDED.change_percent,
                        source = EXCLUDED.source,
                        received_at = EXCLUDED.received_at
                """,
                instrumentId,
                java.sql.Timestamp.valueOf(LocalDateTime.parse(receivedAtIso)),
                new BigDecimal(lastPrice),
                new BigDecimal(bidPrice),
                new BigDecimal(askPrice),
                new BigDecimal(changePercent),
                java.sql.Timestamp.valueOf(LocalDateTime.parse(receivedAtIso))
        );
    }

    private Long findOrderId(String clientOrderId) {
        return jdbcTemplate.queryForObject("SELECT id FROM orders WHERE client_order_id = ?", Long.class, clientOrderId);
    }

    private void createMarketOrder(Long strategyRunId, Long instrumentId, String side, String quantity, String clientOrderId)
            throws Exception {
        String payload = orderPayload(strategyRunId, instrumentId, side, quantity, "MARKET", null, clientOrderId);

        mockMvc.perform(authorizedPost("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"));
    }

    private String orderPayload(
            Long strategyRunId,
            Long instrumentId,
            String side,
            String quantity,
            String orderType,
            String limitPrice,
            String clientOrderId
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", findAccountIdByStrategyRun(strategyRunId));
        payload.put("strategyRunId", strategyRunId);
        payload.put("instrumentId", instrumentId);
        payload.put("side", side);
        payload.put("quantity", quantity);
        payload.put("orderType", orderType);
        payload.put("timeInForce", "DAY");
        if (limitPrice != null) {
            payload.put("limitPrice", limitPrice);
        }
        payload.put("clientOrderId", clientOrderId);
        return objectMapper.writeValueAsString(payload);
    }

    private MockHttpServletRequestBuilder authorizedPost(String url) {
        return post(url).header("Authorization", "Bearer " + adminToken);
    }

    private MockHttpServletRequestBuilder authorizedGet(String url) {
        return get(url).header("Authorization", "Bearer " + adminToken);
    }

    private String issueGuestSessionToken() throws Exception {
        String response = mockMvc.perform(post("/app/auth/guest"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode payload = objectMapper.readTree(response);
        return payload.get("accessToken").asText();
    }
}
