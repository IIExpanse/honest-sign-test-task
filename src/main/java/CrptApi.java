import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Класс для работы с единым API Честного знака по созданию документов.
 * Все классы (согласно требованию) собраны в одном месте, поэтому код в целом громоздкий и нуждается в декомпозиции.
 * Из соображений экономии места разделение на модельные классы (использующиеся при работе с базой данных) и DTO не проводилось.
 */
public class CrptApi {
    /**
     * Мапа с опциональными кодами товаров, которые могут быть включены в запрос к API согласно спецификации.
     */
    private final Map<ProductGroup, Integer> productCodes = Map.of(
            ProductGroup.CLOTHES, 1,
            ProductGroup.SHOES, 2,
            ProductGroup.TOBACCO, 3,
            ProductGroup.PERFUMERY, 4,
            ProductGroup.TIRES, 5,
            ProductGroup.ELECTRONICS, 6,
            ProductGroup.PHARMA, 7,
            ProductGroup.MILK, 8,
            ProductGroup.BICYCLE, 9,
            ProductGroup.WHEELCHAIRS, 10
    );
    private final HttpClient client = HttpClient.newHttpClient();
    /**
     * В классе используется единственная внешняя библиотека jackson-databind для работы с JSON.
     */
    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * Очередь с концами временных отрезков, позволяющая соблюсти условие "не более n запросов в промежуток времени".
     */
    private final Queue<Instant> blockedUntil = new ArrayDeque<>();
    private final String URL = "https://ismp.crpt.ru/api/v3";
    private final String URN = "/api/v3/lk/documents/create";
    private final String URI = URL + URN;
    /**
     * Токен авторизации, который, согласно спецификации, у нас должен быть на момент обращения к API.
     */
    private final String token = "secret-token";
    private final TimeUnit timeUnit;
    private final Lock lock = new ReentrantLock(true);
    private final long timeAmount;
    private final int requestLimit;

    /**
     * Длительность временного промежутка равна 1 единице времени, если не определена явно в конструкторе.
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        this.timeAmount = 1;
        this.requestLimit = requestLimit;
    }

    public CrptApi(TimeUnit timeUnit, long timeAmount, int requestLimit) {
        this.timeUnit = timeUnit;
        this.timeAmount = timeAmount;
        this.requestLimit = requestLimit;
    }

    /**
     * Метод для создания документа для ввода в оборот товара, произведенного в РФ, а также других типов документов.
     * Текущая реализация подразумевает сериализацию документа только в формате JSON, но это скорее вопрос подключения подходящей библиотеки.
     *
     * @param document  - информация о документе, которую требуется сохранить;
     * @param group     - группа товаров, код которой может быть включен в запрос на создание документа. Может быть Null.
     * @param signature - электронная подпись.
     * @param type      - тип сохраняемого документа.
     * @return строку с уникальным идентификатором документа или null в случае ошибки.
     * @throws JacksonException     - при проблемах с сериализацией / десериализацией.
     * @throws InterruptedException - при прерывании потока.
     * @throws HttpTimeoutException - при превышении времени ожидания ответа.
     */
    public String createDocument(ProductDocument document,
                                  ProductGroup group,
                                  String signature,
                                  DocumentType type) {
        try {
            lock.lock();

            if (blockedUntil.size() == requestLimit && blockedUntil.peek().isAfter(Instant.now())) {
                Thread.sleep(ChronoUnit.MILLIS.between(Instant.now(), blockedUntil.peek()) + 1);
            }

            while (!blockedUntil.isEmpty() && blockedUntil.peek().isBefore(Instant.now())) {
                blockedUntil.poll();
            }
            blockedUntil.add(Instant.now().plus(Duration.of(timeAmount, timeUnit.toChronoUnit())));

        } catch (Exception e) {
            System.out.println("Ошибка при управлении блокировкой потоков: " + e.getMessage());

        } finally {
            if (Thread.holdsLock(lock)) {
                lock.unlock();
            }
        }

        try {
            DocumentDraft documentDraft = new DocumentDraft(
                    DocumentFormat.MANUAL,
                    document,
                    group != null ? productCodes.get(group) : null,
                    signature,
                    type
            );
            String body = mapper.writeValueAsString(documentDraft);
            APIResponse answer = mapper.readValue(sendRequest(URI, body, token), APIResponse.class);

            if (answer.getValue() != null) {
                return answer.getValue();
            }
            if (answer.getCode() != null) {
                System.out.printf("API вернуло код %s с ошибкой %s: %s",
                        answer.getCode(),
                        answer.getErrorMessage(),
                        answer.getDescription());

            } else {
                System.out.println("Ошибка: получен пустой ответ.");
            }

        } catch (JacksonException e) {
            System.out.println("Ошибка при сериализации / десериализации: " + e.getMessage());

        } catch (InterruptedException e) {
            System.out.println("Ошибка прерывания потока: " + e.getMessage());

        } catch (HttpTimeoutException e) {
            System.out.println("Ошибка, истекло время ожидания ответа: " + e.getMessage());

        } catch (Exception e) {
            System.out.println("Неизвестная ошибка при отправке запроса: " + e.getMessage());
        }

        return null;
    }

    private String sendRequest(String URI, String body, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(URI))
                .setHeader("content-type", "application/json")
                .setHeader("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.of(5, ChronoUnit.SECONDS))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        return response.body();
    }

    class ProductDocument {
        private final Description description;
        private final String docId;
        private final String docStatus;
        private final DocumentType docType;
        private final Boolean importRequest;
        private final String ownerInn;
        private final String participantInn;
        private final String producerInn;
        private final LocalDateTime productionDate;
        private final ProductionType productionType;
        private final List<Product> products;
        private final LocalDateTime regDate;
        private final String regNumber;

        public ProductDocument(
                Description description,
                String docId,
                String docStatus,
                DocumentType docType,
                Boolean importRequest,
                String ownerInn,
                String participantInn,
                String producerInn,
                LocalDateTime productionDate,
                ProductionType productionType,
                List<Product> products,
                LocalDateTime regDate,
                String regNumber) {
            this.description = description;
            this.docId = docId;
            this.docStatus = docStatus;
            this.docType = docType;
            this.importRequest = importRequest;
            this.ownerInn = ownerInn;
            this.participantInn = participantInn;
            this.producerInn = producerInn;
            this.productionDate = productionDate;
            this.productionType = productionType;
            this.products = products;
            this.regDate = regDate;
            this.regNumber = regNumber;
        }

        public Description getDescription() {
            return description;
        }

        public String getDocId() {
            return docId;
        }

        public String getDocStatus() {
            return docStatus;
        }

        public DocumentType getDocType() {
            return docType;
        }

        public Boolean getImportRequest() {
            return importRequest;
        }

        public String getOwnerInn() {
            return ownerInn;
        }

        public String getParticipantInn() {
            return participantInn;
        }

        public String getProducerInn() {
            return producerInn;
        }

        public LocalDateTime getProductionDate() {
            return productionDate;
        }

        public ProductionType getProductionType() {
            return productionType;
        }

        public List<Product> getProducts() {
            return products;
        }

        public LocalDateTime getRegDate() {
            return regDate;
        }

        public String getRegNumber() {
            return regNumber;
        }
    }

    class Description {
        private final String participantInn;

        public Description(String participantInn) {
            this.participantInn = participantInn;
        }

        public String getParticipantInn() {
            return participantInn;
        }
    }

    class Product {
        private final CertificateDocument certificateDocument;
        private final LocalDateTime certificateDocumentDate;
        private final String certificateDocumentNumber;
        private final String ownerInn;
        private final String producerInn;
        private final LocalDateTime productionDate;
        private final String tnvedCode;
        private final String uitCode;

        public Product(
                CertificateDocument certificateDocument,
                LocalDateTime certificateDocumentDate,
                String certificateDocumentNumber,
                String ownerInn,
                String producerInn,
                LocalDateTime productionDate,
                String tnvedCode,
                String uitCode) {
            this.certificateDocument = certificateDocument;
            this.certificateDocumentDate = certificateDocumentDate;
            this.certificateDocumentNumber = certificateDocumentNumber;
            this.ownerInn = ownerInn;
            this.producerInn = producerInn;
            this.productionDate = productionDate;
            this.tnvedCode = tnvedCode;
            this.uitCode = uitCode;
        }

        public CertificateDocument getCertificateDocument() {
            return certificateDocument;
        }

        public LocalDateTime getCertificateDocumentDate() {
            return certificateDocumentDate;
        }

        public String getCertificateDocumentNumber() {
            return certificateDocumentNumber;
        }

        public String getOwnerInn() {
            return ownerInn;
        }

        public String getProducerInn() {
            return producerInn;
        }

        public LocalDateTime getProductionDate() {
            return productionDate;
        }

        public String getTnvedCode() {
            return tnvedCode;
        }

        public String getUitCode() {
            return uitCode;
        }
    }

    /**
     * Класс запроса на создание документа, определенный на стр. 44 спецификации.
     */
    class DocumentDraft {
        private final DocumentFormat documentFormat;
        private final ProductDocument productDocument;
        private final Integer productGroup;
        private final String signature;
        private final DocumentType type;

        public DocumentDraft(DocumentFormat documentFormat,
                             ProductDocument productDocument,
                             Integer productGroup,
                             String signature,
                             DocumentType type) {
            this.documentFormat = documentFormat;
            this.productDocument = productDocument;
            this.productGroup = productGroup;
            this.signature = signature;
            this.type = type;
        }

        public DocumentFormat getDocumentFormat() {
            return documentFormat;
        }

        public ProductDocument getProductDocument() {
            return productDocument;
        }

        public Integer getProductGroup() {
            return productGroup;
        }

        public String getSignature() {
            return signature;
        }

        public DocumentType getType() {
            return type;
        }
    }

    /**
     * Класс ответа на запрос, содержащий либо идентификатор документа, либо информацию об ошибке.
     */
    class APIResponse {
        private String value;
        private String code;
        private String errorMessage;
        private String description;

        public APIResponse() {
        }

        public APIResponse(String value, String code, String errorMessage, String description) {
            this.value = value;
            this.code = code;
            this.errorMessage = errorMessage;
            this.description = description;
        }

        public String getValue() {
            return value;
        }

        public String getCode() {
            return code;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getDescription() {
            return description;
        }
    }

    enum ProductionType {
        OWN_PRODUCTION,
        CONTRACT_PRODUCTION
    }

    enum CertificateDocument {
        CONFORMITY_CERTIFICATE,
        CONFORMITY_DECLARATION
    }

    enum DocumentFormat {
        MANUAL,
        XML,
        CSV
    }

    enum ProductGroup {
        CLOTHES,
        SHOES,
        TOBACCO,
        PERFUMERY,
        TIRES,
        ELECTRONICS,
        PHARMA,
        MILK,
        BICYCLE,
        WHEELCHAIRS
    }

    enum DocumentType {
        AGGREGATION_DOCUMENT,
        DISAGGREGATION_DOCUMENT,
        REAGGREGATION_DOCUMENT,
        LP_INTRODUCE_GOODS,
        LP_SHIP_GOODS,
        LP_ACCEPT_GOODS,
        LK_REMARK,
        LK_RECEIPT,
        LP_GOODS_IMPORT,
        LP_CANCEL_SHIPMENT,
        LK_KM_CANCELLATION,
        LK_APPLIED_KM_CANCELLATION,
        LK_CONTRACT_COMMISSIONING,
        LK_INDI_COMMISSIONING,
        LP_SHIP_RECEIPT,
        OST_DESCRIPTION,
        CROSSBORDER,
        LP_INTRODUCE_OST,
        LP_RETURN,
        LP_SHIP_GOODS_CROSSBORDER,
        LP_CANCEL_SHIPMENT_CROSSBORDER
    }
}
