package com.merchantslipurl.web;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import com.merchantslipurl.persistence.BindingRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MerchantSlipUrlPageE2E {

	@LocalServerPort
	private int port;

	@Autowired
	private BindingRepository bindingRepository;

	private MockWebServer server;

	private Playwright playwright;

	private Browser browser;

	private Page page;

	@BeforeEach
	void setUp() throws IOException {
		bindingRepository.deleteAll();
		server = new MockWebServer();
		server.start();
		playwright = Playwright.create();
		browser = playwright.chromium().launch();
		page = browser.newPage();
	}

	@AfterEach
	void tearDown() throws IOException {
		if (browser != null) {
			browser.close();
		}
		if (playwright != null) {
			playwright.close();
		}
		if (server != null) {
			server.shutdown();
		}
	}

	@Test
	void merchantPage_showsSignInAndDestinationForm() {
		page.navigate(baseUrl());

		PlaywrightAssertions.assertThat(heading("MerchantSlipUrl")).isVisible();
		PlaywrightAssertions.assertThat(page.getByLabel("Username")).hasValue("merchant");
		PlaywrightAssertions.assertThat(page.getByLabel("Password")).hasValue("merchant");
		PlaywrightAssertions.assertThat(page.getByLabel("Long URL")).isVisible();
		PlaywrightAssertions.assertThat(page.getByLabel("Vanity")).isVisible();
		PlaywrightAssertions.assertThat(button("Create tiny URL")).isVisible();
		PlaywrightAssertions.assertThat(button("Assign vanity")).isVisible();
		PlaywrightAssertions.assertThat(page.getByRole(AriaRole.HEADING, named("Open a tiny URL"))).isVisible();
	}

	@Test
	void createTinyUrl_showsCreatedLinkAndSessionHistory() {
		server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
		String destination = server.url("/checkout?orderId=42").toString();

		page.navigate(baseUrl());
		page.getByLabel("Long URL").fill(destination);
		button("Create tiny URL").click();

		PlaywrightAssertions.assertThat(page.locator("[data-stamp]")).hasText("Created");
		PlaywrightAssertions.assertThat(page.locator("a.tiny")).containsText("http://localhost:8080/");
		PlaywrightAssertions.assertThat(page.locator("a.original")).hasText(destination);
		PlaywrightAssertions.assertThat(heading("This session")).isVisible();
		PlaywrightAssertions.assertThat(page.locator("#history-list")).containsText(destination);
		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void rejectedCredentials_showSignInAlert() {
		String destination = server.url("/checkout?orderId=7").toString();

		page.navigate(baseUrl());
		page.getByLabel("Password").fill("wrong");
		page.getByLabel("Long URL").fill(destination);
		button("Create tiny URL").click();

		PlaywrightAssertions.assertThat(page.getByRole(AriaRole.ALERT))
				.hasText("Those credentials were rejected. Create and assign need the merchant username and password.");
		PlaywrightAssertions.assertThat(page.locator("#outcome")).isHidden();
		assertThat(bindingRepository.count()).isZero();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void assignVanity_showsAssignedStamp() {
		server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
		String destination = server.url("/sale").toString();

		page.navigate(baseUrl());
		page.getByLabel("Long URL").fill(destination);
		button("Create tiny URL").click();
		PlaywrightAssertions.assertThat(page.locator("[data-stamp]")).hasText("Created");

		page.getByLabel("Vanity").fill("spring-sale");
		button("Assign vanity").click();

		PlaywrightAssertions.assertThat(page.locator("[data-stamp]")).hasText("Vanity assigned");
		PlaywrightAssertions.assertThat(page.locator("a.tiny")).hasText("http://localhost:8080/spring-sale");
		PlaywrightAssertions.assertThat(page.locator("a.original")).hasText(destination);
		assertThat(bindingRepository.findByShortCode("spring-sale")).isPresent();
		assertThat(bindingRepository.count()).isEqualTo(1);
	}

	@Test
	void assignWithoutVanity_staysOnTheForm() {
		page.navigate(baseUrl());
		page.getByLabel("Long URL").fill("https://orders.example.com/checkout");
		button("Assign vanity").click();

		PlaywrightAssertions.assertThat(page.locator("#outcome")).isHidden();
		assertThat(page.getByLabel("Vanity").evaluate("el => el.validationMessage"))
				.isEqualTo("Enter a vanity to assign.");
		assertThat(bindingRepository.count()).isZero();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void openShortCode_followsRedirectToDestination() {
		server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
		server.enqueue(new MockResponse().setResponseCode(200).setBody("landed"));
		String destination = server.url("/checkout?orderId=42").toString();

		page.navigate(baseUrl());
		page.getByLabel("Long URL").fill(destination);
		button("Create tiny URL").click();
		PlaywrightAssertions.assertThat(page.locator("[data-stamp]")).hasText("Created");

		String tinyUrl = page.locator("a.tiny").textContent();
		String shortCode = tinyUrl.substring(tinyUrl.lastIndexOf('/') + 1);
		page.getByLabel("Short code or tiny URL").fill(shortCode);

		Page popup = page.waitForPopup(() -> button("Open").click());
		popup.waitForURL(destination);
		assertThat(popup.url()).isEqualTo(destination);
	}

	private String baseUrl() {
		return "http://127.0.0.1:" + port + "/";
	}

	private com.microsoft.playwright.Locator heading(String name) {
		return page.getByRole(AriaRole.HEADING, named(name));
	}

	private com.microsoft.playwright.Locator button(String name) {
		return page.getByRole(AriaRole.BUTTON, named(name).setExact(true));
	}

	private static Page.GetByRoleOptions named(String name) {
		return new Page.GetByRoleOptions().setName(name);
	}
}
