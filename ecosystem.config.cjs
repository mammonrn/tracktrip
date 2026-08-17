module.exports = {
  apps: [
    {
      name: 'trip-tracker',
      script: 'src/index.js',
      cwd: __dirname,
      env: {
        NODE_ENV: 'production',
      },
    },
  ],
};
