package com.tarento.analytics;

import com.tarento.analytics.constant.Constants;
import org.cache2k.extra.spring.SpringCache2kCacheManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import javax.net.ssl.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;



import java.util.concurrent.TimeUnit;


@SpringBootApplication
@EnableCaching
public class AnalyticApp {
	public static void trustSelfSignedSSL() {
		try {
			SSLContext ctx = SSLContext.getInstance("TLS");
			X509TrustManager tm = new X509TrustManager() {
				public void checkClientTrusted(X509Certificate[] xcs, String string) throws CertificateException {
				}
				public void checkServerTrusted(X509Certificate[] xcs, String string) throws CertificateException {
				}
				public X509Certificate[] getAcceptedIssuers() {
					return null;
				}
			};
			ctx.init(null, new TrustManager[]{tm}, null);
			SSLContext.setDefault(ctx);
			// Disable hostname verification
			HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
				public boolean verify(String hostname, javax.net.ssl.SSLSession sslSession) {
					return true;
				}
			});
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
	 public static void main( String[] args ) {
		trustSelfSignedSSL();
		SpringApplication.run(AnalyticApp.class, args);
	    }

		@Value("${cache.expiry.time.in.minutes}")
		private int cacheExpiry;

		@Value("${cache.capacity}")
		private int cacheCapacity;

	    @Bean
	    public RestTemplate restTemplate() {
	        return new RestTemplate();
	    }


	@Bean
	public WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				registry.addMapping("/**")
						.allowedMethods(Constants.GET, Constants.POST, Constants.PUT, Constants.DELETE, Constants.OPTIONS)
						.allowedOrigins("*")
						.allowedHeaders("*");
			}
		};
	}

		@Bean
		@Profile("!test")
		public CacheManager cacheManager(){
			return new SpringCache2kCacheManager().addCaches(b->b.name("versions").expireAfterWrite(cacheExpiry, TimeUnit.MINUTES)
					.entryCapacity(cacheCapacity));
		}
}
