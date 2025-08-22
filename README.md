# AI Alfresco

> **Beta version – This project is under active development and may be subject to changes.**

This project integrates **Alfresco Content Services** with artificial intelligence capabilities using AWS services such as Textract and Translate. It enables automatic metadata extraction and document translation when storing files in Alfresco.

## Compatibility

- **Compatible with Alfresco 7.3 up to Alfresco 23.3**
- Developed with **Alfresco SDK 4.9**

## Main Features

- **Metadata extraction** from invoices and tickets using Amazon Textract.
- **Translation** of documents via Amazon Translate (PDF, Word, TXT).
- **Recording text** of documents via Amazon Polly (PDF, Word, TXT).
- **Docker-based architecture** for easy deployment and testing.

## Project Structure

- `ai-alfresco-platform`: Main Alfresco module (Java).
- `ai-alfresco-share`: Share module for the user interface.
- `docker/`: Docker and Docker Compose configuration files.
- `src/main/resources/alfresco/module/ai-alfresco-platform/model/content-model.xml`: Custom data model (AI aspects and properties).

## Requirements

- Docker and Docker Compose
- AWS account and credentials with permissions for Textract, Translate, Polly
- Java 17+ (for development)

## Quick Setup

1. **Clone the repository:**

   ```bash
   git clone https://github.com/SergioCea/ai-alfresco.git
   cd ai-alfresco
   ```

2. **Configure your AWS credentials**  
   AWS keys are passed as environment variables in `docker-compose.yml`:

   ```yaml
   -Daws.translate.key=YOUR_AWS_KEY
   -Daws.translate.secret=YOUR_AWS_SECRET
   -Daws.textract.key=YOUR_AWS_KEY
   -Daws.textract.secret=YOUR_AWS_SECRET
   -Daws.key=YOUR_AWS_KEY
   -Daws.secret=YOUR_AWS_SECRET
   ```

3. **Start the environment with Docker Compose:**

   ```bash
   ./run.bat build_start
   ```

4. **Access Alfresco and Share:**
   - Alfresco ACS: [http://localhost:8080/alfresco](http://localhost:8080/alfresco)
   - Alfresco Share: [http://localhost:8081/share](http://localhost:8180/share)

## Usage

1. **Upload a document** to Alfresco.
2. In the document actions you will have the options to translate, extract metadata or save the content.
3. In the case of translation, you will need to select the input and output languages.
   When extracting metadata, you must select whether it is a ticket or an invoice.

## Data Model Customization

The data model is defined in `content-model.xml` and adds aspects such as `ai:tickets` with custom properties (`ai:ticketDate`, `ai:ticketTax`, `ai:ticketTotal`).

Java property example:

```java
QName.createQName("http://www.ai-alfresco.org/model/content/1.0", "ticketTax")
```

## Development

- Main Java code is in `ai-alfresco-platform/src/main/java/es/sergio/main/`.
- You can debug using the debug ports exposed in the Docker services.
- AWS integrations are in `es.sergio.aws`.

## Security

**Do not include your AWS keys in the repository.** Use environment variables or a secure secrets manager.

## License

Apache License 2.0  
This project is licensed under the terms of the [Apache License 2.0](LICENSE).
