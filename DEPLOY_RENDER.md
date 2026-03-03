# Deploy To Render

## 1) Push code to GitHub
Make sure your latest code is pushed to a GitHub repository.

## 2) Create Web Service in Render
1. Open Render dashboard.
2. Click `New` -> `Web Service`.
3. Connect your GitHub repo.
4. Render will detect `render.yaml` automatically.

If not using blueprint auto-detect, use:
- Runtime: `Java`
- Build Command: `mvn -DskipTests clean package`
- Start Command: `java -jar target/*.jar`

## 3) Set API key (important)
Do **not** put the key in source code or `application.properties`.

In Render:
1. Open your service.
2. Go to `Environment`.
3. Add env var:
   - Key: `GEMINI_API_KEY`
   - Value: your Gemini API key
4. Save changes and redeploy.

## 4) Deploy and test
1. Trigger deploy.
2. Open the Render URL: `https://<your-service-name>.onrender.com`
3. Test the chatbot and `/api/chat` flow.

## Notes
- App is configured with `server.port=${PORT:8080}` for Render compatibility.
- Free tier can cold-start after inactivity.
