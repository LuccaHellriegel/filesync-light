FROM node:18-alpine
RUN mkdir /app
COPY ./src /app/src
COPY ./package.json /app
COPY ./package-lock.json /app
COPY ./tsconfig.json /app
WORKDIR /app
RUN npm install && npm run build

ENTRYPOINT ["node", "build/index.js"]